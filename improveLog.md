# 缓存击穿（互斥锁方案）改进日志

日期：2026-08-13

## 背景

`ShopServiceImpl.queryById` 使用「互斥锁（setnx）+ 递归重试 + 双重检查」的方案解决缓存击穿问题。在实现中发现并修复了两个与锁相关的 bug。

---

## Bug 1：锁误删 —— 拿锁失败的线程也会释放锁

### 问题描述

`tryLock` 失败（即没拿到锁）的线程，在递归重试返回之前，仍会执行 `finally` 里的 `unLock`，把**别的线程已经持有的锁**删掉。

### 原因分析

`try/finally` 的作用域写错了：`tryLock` 失败的分支也在 `try` 块里，所以那个分支的 `return` 会先触发 `finally`，导致没有持有锁的线程也执行了释放锁的操作。

### 错误代码

```java
try {
    LockOfKey = RedisConstants.LOCK_SHOP_KEY + id;
    boolean isLock = tryLock(LockOfKey);
    if (!isLock) {                      // 拿锁失败的线程
        Thread.sleep(100);
        return queryWithMutex(id);      // 这里会先执行 finally
    }
    // ...重建缓存...
} finally {
    unLock(LockOfKey);                  // 拿锁失败的线程也执行了这里，误删别人的锁
}
```

**后果**：线程 B 没拿到锁，`return` 之前 `finally` 删掉了线程 A 持有的锁；线程 C 又能拿到锁、也去重建缓存，互斥彻底失效，缓存击穿问题重新出现。

### 修复方案

把拿锁失败的分支挪到 `try/finally` **之外**，保证只有真正持有锁的线程才会释放锁：

```java
String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
String lockValue = tryLock(lockKey);        // 拿到锁返回 UUID，拿不到返回 null

// 拿锁失败：睡一会再递归重试（不进 finally，不会误删别人的锁）
if (lockValue == null) {
    try {
        Thread.sleep(100);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    return queryWithMutex(id);
}

// 拿锁成功：双重检查后再重建缓存
try {
    // ...重建缓存...
} finally {
    unLock(lockKey, lockValue);             // 只有持有锁的线程才会走到这里
}
```

---

## Bug 2：释放锁未校验所有权 —— 误删「已过期被他人抢走的锁」

### 问题描述

`unLock` 直接按 key `delete`，不校验锁当前的值是否还是自己加锁时的值。当锁因 TTL 过期被别的线程重新获取后，原线程仍会把它删掉。

### 原因分析

锁在 `tryLock` 里设置了 10 秒 TTL（防止线程崩溃后锁永久不释放）。如果某线程重建缓存耗时超过 10 秒，锁自动过期，被其他线程抢走并写入了**新的锁值**；原线程结束后执行 `delete(key)`，就会把**新线程的锁**误删掉。

### 错误代码

```java
private boolean tryLock(String key) {
    // 锁的 value 固定为 "1"，无法区分锁是谁的
    Boolean setnx = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
    return BooleanUtil.isTrue(setnx);
}

private void unLock(String key) {
    stringRedisTemplate.delete(key);        // 不看 value，直接删，可能删错
}
```

**后果**：锁过期易主后，旧线程把新线程的锁删掉，互斥失效。

### 修复方案

加锁时用 **UUID** 作为锁的唯一标识，释放时用 **Lua 脚本原子地「先比对 value 再删除」**，保证「是自己的锁才删」：

```java
// 加锁：value 用 UUID，只有自己认得出
private String tryLock(String key) {
    String uuid = UUID.randomUUID().toString();
    Boolean setnx = stringRedisTemplate.opsForValue().setIfAbsent(key, uuid, 10, TimeUnit.SECONDS);
    return BooleanUtil.isTrue(setnx) ? uuid : null;   // 拿到锁返回 UUID，否则返回 null
}

// Lua 脚本：值相同才删，否则不动
private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "  return redis.call('del', KEYS[1]) " +
        "else " +
        "  return 0 " +
        "end",
        Long.class);

// 释放锁：Redis 单线程执行 Lua，get + del 原子完成，不会被打断
private void unLock(String key, String value) {
    stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), value);
}
```

**为什么用 Lua**：Redis 执行 Lua 脚本是原子的，`get`（判断）和 `del`（删除）之间不会有其他命令插入，从而彻底避免「判断通过后、删除前」这段时间窗口内锁易主导致的误删。若用 Java 代码分两步 `get` 再 `delete`，两步之间仍存在竞争窗口。

---

## 改善：二次校验（Double Check）—— 避免重复重建缓存

### 这段代码在做什么

获取到锁之后，先**再查一次缓存**，确认在自己等待锁的这段时间里，缓存是否已经被别的线程重建了：

```java
// 3.1.2.1 再次检查缓存是否建立，已存在则返回
String newShopJson = stringRedisTemplate.opsForValue().get(key);
if (StrUtil.isNotBlank(newShopJson)) {
    return JSONUtil.toBean(newShopJson, Shop.class);
}
// 确实不存在，重建缓存
```

### 为什么需要二次校验

线程从「第一次查缓存未命中」到「真正拿到锁」之间存在时间窗口。在这个窗口里，可能发生：

1. 线程 A 拿到锁 → 查 DB → 写入缓存 → 释放锁；
2. 线程 B 此时才拿到锁。

如果 B 拿到锁后直接查 DB 重建缓存，就会**重复查询数据库、重复写缓存**。二次校验就是为了拦住这种情况：拿到锁先看缓存，发现已经被别人重建好了，就直接返回，不再重复重建。

### 修复前的问题

最初版本虽然也做了二次校验，但反序列化用的是**第一次查询时的旧变量 `shopJson`**（走到这里时它一定是 `null`），导致即使缓存里已经有数据，也返回了 `null`：

```java
// 错误：判断用的是新读的值，反序列化却用了旧的 null
if (StrUtil.isNotBlank(stringRedisTemplate.opsForValue().get(key))) {
    return JSONUtil.toBean(shopJson, Shop.class);   // shopJson 是 null → 返回 null
}
```

### 修复方案

重新读取的结果接到**新变量** `newShopJson`，判断和反序列化都用它，并且只读一次缓存：

```java
String newShopJson = stringRedisTemplate.opsForValue().get(key);
if (StrUtil.isNotBlank(newShopJson)) {
    return JSONUtil.toBean(newShopJson, Shop.class);
}
```

---

## 总结

| Bug / 改善 | 原因 | 修复 |
|-----|------|------|
| 锁误删 | `try/finally` 作用域错误，拿锁失败的线程也执行释放 | 拿锁失败分支移出 `try/finally`，只有持锁线程释放 |
| 锁重复删除 / 删错锁 | `unLock` 按 key 直接 `delete`，不校验锁归属 | 锁值用 UUID，释放用 Lua 原子「比对后删除」 |
| 二次校验返回 null | 反序列化用了旧的 `shopJson`（null） | 重新读缓存接到新变量，判断与反序列化都用新值 |

---

# 工具类抽取：CacheClient 统一封装缓存读写

> 日期：2026-08-14

## 背景

`ShopServiceImpl` 里集中了大量缓存相关逻辑：`queryWithPassThrough`（穿透+雪崩）、`queryWithLogicalExpire`（击穿-逻辑过期）、`queryWithMutex`（击穿-互斥锁）、`tryLock`/`unLock`、`saveShopToRedisWithExpire` 等。这些方法写死了 `Shop.class`、`RedisConstants.CACHE_SHOP_KEY`、`RedisConstants.CACHE_SHOP_TTL`，跟店铺实体强耦合——其它实体（如秒杀券、用户）要用同一套缓存方案，只能复制粘贴再改一遍。

## 方案

把这些方法抽取到一个通用的 `CacheClient` 工具类（`com.hmdp.utils.CacheClient`），用**泛型**解决返回类型不同的问题，用 **`Function` 函数式接口**把「查数据库」这个动作以回调的形式交给调用方，从而把缓存读写与具体业务实体彻底解耦。

## 抽取后的类结构

### 写入方法

| 方法 | 职责 | 对应原方法 |
|-----|------|-----------|
| `set(key, value, time, unit)` | 存入数据并设置过期时间 | 原 `stringRedisTemplate.opsForValue().set(...)` |
| `setWithLogicalExpire(key, value, time, unit)` | 用 `RedisData` 包装，存入数据并附带「逻辑过期时间」 | `saveShopToRedisWithExpire` |

### 查询方法

| 方法 | 解决的缓存问题 | 对应原方法 |
|-----|--------------|-----------|
| `queryWithPassThrough(keyPrefix, id, type, dbFallBack, time, unit)` | 穿透 + 雪崩（空值拦截 + 随机 TTL） | `queryWithPassThrough` |
| `queryWithLogicalExpire(keyPrefix, id, type, dbFallBack, time, unit)` | 击穿（逻辑过期 + 线程池异步重建 + 互斥锁） | `queryWithLogicalExpire` |

### 锁辅助方法（private）

| 方法 | 职责 |
|-----|------|
| `tryLock(key)` | setnx 加锁，value 用 UUID；拿到返回 UUID，拿不到返回 null |
| `unLock(key, value)` | Lua 原子「比对 value 再删除」，只删自己的锁 |

## 关键设计：泛型 + Function 解耦

```java
public <R, ID> R queryWithPassThrough(
        String keyPrefix,           // key 前缀由调用方传入，不再是写死的 CACHE_SHOP_KEY
        ID id,                      // 主键类型泛型化
        Class<R> type,              // 返回实体类型泛型化，不再是写死的 Shop.class
        Function<ID, R> dbFallBack, // 查数据库的动作交给调用方（方法引用）
        Long time, TimeUnit unit) {
    ...
    R r = dbFallBack.apply(id);     // 只在这里回调调用方的查询逻辑
    ...
}
```

调用方（`ShopServiceImpl.queryById`）只需一行：

```java
Shop shop = cacheClient.queryWithLogicalExpire(
        RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, 10L, TimeUnit.SECONDS);
```

`this::getById` 就是 `Function<Long, Shop>`，把「怎么查数据库」留给业务层，`CacheClient` 只负责「怎么查/写缓存」这一通用套路。

## 收益

- **复用**：店铺、券、用户等任意实体只需传 key 前缀、实体类型、查库回调，即可复用同一套缓存策略，无需复制粘贴。
- **解耦**：`CacheClient` 不再依赖任何具体实体或常量，业务层 `ShopServiceImpl` 从一堆缓存代码瘦身为几行调用。
- **职责清晰**：缓存读写与业务查询分离，`Service` 只关心业务，工具类只关心缓存。

## 说明

- `queryWithMutex`（互斥锁方案）本次未迁入 `CacheClient`，`ShopServiceImpl` 中相关代码以注释形式保留，后续如需复用可同样抽取。
- 逻辑过期重建的线程池 `CACHE_REBUILD_EXECUTOR`（10 线程）随 `setWithLogicalExpire`/`queryWithLogicalExpire` 一并迁入工具类。

---

# 优惠券秒杀：超卖问题与「一人一单」限制

> 日期：2026-08-15

## 背景

秒杀接口 `VoucherOrderServiceImpl.seckillServer` 在并发下存在两个维度的超卖：

1. **全局库存超卖**：多个用户并发抢同一份库存，`stock - 1` 的更新互相覆盖，导致库存被扣成负数、实际卖出的数量超过库存。
2. **单人重复下单**：同一个用户用多个线程并发请求，突破「一人一单」的限制，重复扣库存、生成多张订单。

## 解决方案

### 1. 乐观锁（CAS）解决全局库存超卖

扣减库存时不再无条件 `stock = stock - 1`，而是带上「库存必须大于 0」的 WHERE 条件，用数据库行锁实现乐观锁：

```java
boolean success = seckillVoucherService.update()
        .setSql("stock = stock - 1")
        .eq("voucher_id", voucherId)
        .gt("stock", 0)        // 关键：stock > 0 才更新，等价于 CAS 的版本判断
        .update();
if (!success) {
    return Result.fail("已售罄");
}
```

- UPDATE 语句本身是原子的，`stock > 0` 不成立的行不会被扣减，因此库存永远不会变成负数。
- 多线程并发扣减时，数据库行锁让它们串行执行；最后一个成功时库存恰好归零，后续线程因 `stock > 0` 不成立而更新失败、返回「已售罄」。

### 2. 「一人一单」：下单前校验是否已购买

扣减库存、创建订单之前，先查当前用户是否已购买过该券：

```java
Integer userCount = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
if (userCount > 0) {
    return Result.fail("购买数量已达上限");
}
```

### 3. 悲观锁串行化同一用户的并发请求

仅靠 count 校验挡不住「同一用户的多个线程同时通过校验」的竞态——线程 A、B 都查到 `userCount == 0`，随后都去扣库存、建订单。因此在入口按用户 ID 加悲观锁，把同一用户的请求串行化：

```java
Long userId = UserHolder.getUser().getId();
synchronized (userId.toString().intern()) {
    // 事务提交后再释放锁
    IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    return proxy.createVoucherOrder(voucherId);
}
```

- `synchronized (userId.toString().intern())`：同一用户 ID 的请求共用一把锁，多线程只能排队执行；第二个线程进入时 `userCount` 已 > 0，被「一人一单」拦住。
- 锁的范围包住整个下单逻辑，**事务提交后才释放锁**，保证「查询 → 扣减 → 建单」对同一用户是原子串行的。

### 4. 通过代理调用，保证事务生效

`@Transactional` 加在 `createVoucherOrder` 上，而 `seckillServer` 本身无事务注解。若直接 `this.createVoucherOrder(...)`，属于类内部自调用，Spring AOP 不拦截，`@Transactional` 会失效。因此用 `AopContext.currentProxy()` 拿到代理对象、通过代理调用，事务才真正生效，扣库存 + 建订单落在同一事务里。

## 整体时序

```
seckillServer（无事务）
  ├─ 校验活动时间、库存 > 0
  ├─ synchronized(userId) 加悲观锁        ← 同一用户串行
  │    └─ proxy.createVoucherOrder()（事务）
  │         ├─ 查 userCount，>0 则拦截     ← 一人一单
  │         ├─ 乐观锁扣库存 stock=stock-1 where stock>0
  │         └─ 保存订单
  └─ 事务提交后释放锁
```

## 两个维度的超卖对应关系

| 超卖维度 | 手段 | 实现 |
|---------|------|------|
| 全局库存超卖（多用户抢同一库存） | 乐观锁（CAS） | 扣减 SQL 加 `gt("stock", 0)` |
| 单人重复下单（同一用户多线程） | 悲观锁 + 一人一单校验 | `synchronized(userId)` + count 校验 |

## 遗留问题与建议（未改代码）

1. **`synchronized` 只能保证单机**：JVM 内置锁无法跨进程，集群/多实例部署时同一用户请求可能落到不同实例，互斥失效，「一人一单」仍可能被并发打破。分布式场景应改用 Redis 分布式锁（setnx + UUID + Lua 释放，可复用本篇上文 `CacheClient` 的 `tryLock`/`unLock` 思路）。
2. **一人一单缺少数据库兜底**：`count 校验 + insert` 之间仍存在竞态窗口，且 `synchronized` 只在单机有效。最可靠的兜底是给订单表 `(user_id, voucher_id)` 建唯一索引，从数据库层彻底杜绝一人多单。
3. **`AopContext.currentProxy()` 的前置条件**：需开启 `@EnableAspectJAutoProxy(exposeProxy = true)`，否则 `currentProxy()` 会抛 `IllegalStateException`。✅ 已在启动类 `HmDianPingApplication` 上配置 `@EnableAspectJAutoProxy(exposeProxy = true)`，此项已满足。
4. **`intern()` 的隐患**：`userId.toString().intern()` 会把锁对象放进字符串常量池，大量不同 userId 时不够干净。更稳妥的做法是用 `ConcurrentHashMap<Long, Object>` 缓存每个用户自己的锁对象，或直接用分布式锁替代。

---

# 优惠券秒杀：用 Redis 分布式锁替换 JVM 本地锁

> 日期：2026-08-17

## 背景

上一篇（2026-08-15）里，「一人一单」依赖 `synchronized(userId.toString().intern())` 这个 JVM 内置锁来串行化同一用户的并发请求。它的致命局限是**只能保证单机**：一旦 nginx 把请求负载均衡到 8081 / 8082 两个实例，同一用户的并发请求可能落在不同进程，各自的 JVM 锁互不干扰，「一人一单」就会被并发打破。

## 方案

引入基于 Redis 的分布式锁 `SimpleRedisLock`，用 Redis 的 `SETNX`（`setIfAbsent`）实现跨进程互斥。Redis 是所有实例共享的，锁存到 Redis 里，就能在集群层面统一互斥。

### 1. 新增锁工具类 `SimpleRedisLock implements ILock`

```java
public class SimpleRedisLock implements ILock {
    private String KEY_PREFIX = "lock:";
    private String name;               // 锁的业务标识

    @Override
    public boolean tryLock(long timeoutSec) {
        long threadId = Thread.currentThread().getId();
        Boolean isLock = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isLock);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
```

- `tryLock`：`setIfAbsent` 等价于 `SETNX`，只有 key 不存在时才能设置成功，谁先设置成功谁拿锁；同时带上 TTL，防止持有锁的线程崩溃后锁永不释放。
- `unlock`：直接按 key 删除释放锁。

### 2. `seckillServer` 用分布式锁替换 `synchronized`

```java
// 锁的粒度到用户：lock:order:{userId}
SimpleRedisLock userOrderRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

boolean isLock = userOrderRedisLock.tryLock(1200);
if (!isLock) {
    return Result.fail("请勿重复点击");
}
try {
    IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    return proxy.createVoucherOrder(voucherId);
} finally {
    userOrderRedisLock.unlock();   // 事务提交后再释放
}
```

- 锁 key 为 `lock:order:{userId}`，粒度到「用户」，同一用户的并发请求在集群内共用同一把 Redis 锁。
- 加锁失败（说明该用户已有请求正在处理）直接返回「请勿重复点击」，不做重试。
- 释放放在 `finally`，而 `createVoucherOrder` 是事务方法、提交后才返回，因此**事务提交先于锁释放**，顺序正确。

## 解决了什么

| 之前 | 现在 |
|------|------|
| `synchronized(userId)` 是 JVM 内置锁，仅单机有效 | Redis 分布式锁，8081/8082 多实例共享同一把锁 |
| 集群下同一用户并发请求落到不同实例时互斥失效 | 跨进程也能互斥，一人一单在集群下依然成立 |

至此，「一人一单」从「本地悲观锁」升级为「Redis 分布式锁」，配合上一版的乐观锁（`gt("stock", 0)`）和 count 校验，两个维度的超卖（全局库存 / 单人重复下单）在单机与集群下都得到了控制。

## 遗留问题与建议（未改代码）

这一版解决了分布式互斥，但 `SimpleRedisLock` 本身还有几处隐患，其中第 1 条与 08-13 缓存互斥锁踩过的坑是同款，仅记录、未改代码：

1. **`unlock()` 不校验锁归属，存在误删锁风险**：`unlock()` 直接 `delete(key)`，不判断这把锁还是不是自己的。锁带 TTL，若线程 A 持锁期间执行时间超过 TTL（GC、慢 SQL 等），锁自动过期被线程 B 抢走，A 执行完再 `delete` 就会把 B 的锁误删，互斥再次失效。这与 08-13 缓存互斥锁的「Bug 2：释放锁未校验所有权」完全同款。→ 建议照搬那次的修复：锁 value 用 UUID，释放用 Lua 脚本原子「比对 value 再删除」。
2. **value 用 threadId 但未参与比对，等于白存**：`tryLock` 把 `Thread.currentThread().getId()` 写进 value，`unlock` 却从不读取它。而且线程 ID 跨 JVM 可能重复、同 JVM 也可能复用，即便将来用它做归属判断也不可靠。→ 建议改为 UUID 作为锁唯一标识。
3. **TTL 1200 秒过长**：`tryLock(1200)` 给了 20 分钟过期时间，一旦持锁线程崩溃，该用户在 20 分钟内无法再下单；且配合「无归属校验的 delete」，误删窗口被进一步放大。→ 建议把 TTL 缩短到秒杀业务实际耗时的量级，或配合可重入/续期机制（至少先补上第 1 条的归属校验）。
4. **锁粒度只到用户级**：key 是 `lock:order:{userId}`，同一用户抢不同的券也会被同一把锁串行、甚至被误判为「请勿重复点击」。→ 若希望同一用户抢不同券互不阻塞，key 可细化为 `lock:order:{userId}:{voucherId}`。

---

# 优惠券秒杀：分布式锁解锁改为 Lua 原子释放（补上锁归属校验）

> 日期：2026-08-17

## 背景

上一版 `SimpleRedisLock.unlock()` 直接 `delete(key)`，不校验锁是否还属于自己：锁带 TTL，若持锁线程执行时间超过 TTL，锁被其他线程抢走后，原线程仍会把别人的锁误删，互斥再次失效（与 08-13 缓存互斥锁「Bug 2」同款问题）。同时锁 value 只用 threadId，跨 JVM 可能重复，无法可靠标识锁归属。

## 方案

### 1. 锁 value 增加 JVM 级唯一前缀（UUID + threadId）

```java
private static final String ID_PREFIX = UUID.randomUUID().toString(true);  // JVM 级唯一

// tryLock 时 value = ID_PREFIX + threadId
stringRedisTemplate.opsForValue().setIfAbsent(
    KEY_PREFIX + name, ID_PREFIX + threadId, timeoutSec, TimeUnit.SECONDS);
```

- 线程 ID 在**同一 JVM 内**唯一，但**不同 JVM 的线程 ID 可能相同**（如 8081 和 8082 两个实例都可能有 1 号线程）。
- 用「JVM 级 UUID 前缀 + 线程 ID」拼出 value：同 JVM 内靠 threadId 区分线程，跨 JVM 靠 UUID 前缀区分实例，从而全局唯一地标识「哪台机器的哪个线程持有的锁」。

### 2. 解锁改为 Lua 脚本原子「比对 value 再删除」

新增脚本 `resources/unlock.lua`：

```lua
if(redis.call('get',KEYS[1]) == ARGV[1]) then
    return redis.call('del',KEYS[1])
end
return 0
```

`unlock()` 通过 `DefaultRedisScript` 加载脚本并执行：

```java
stringRedisTemplate.execute(UNLOCK_SCRIPT,
        Collections.singletonList(KEY_PREFIX + name),   // KEYS[1] = 锁的 key
        ID_PREFIX + Thread.currentThread().getId());    // ARGV[1] = 本线程的 value
```

- Redis 单线程执行 Lua 脚本，`get`（比对）和 `del`（删除）之间不会被其他命令插入，**判断 + 释放是原子的**。
- 只有当锁当前的 value 等于本线程的 value（即锁确实属于自己）时才删除；否则返回 0 不动，避免误删别人的锁。

## 解决了什么

| 上一版隐患 | 本次修复 |
|-----------|---------|
| `unlock()` 直接 `delete`，锁易主后误删别人的锁 | Lua 原子「比对 value 再删除」，只有自己的锁才删 |
| value 仅 threadId，跨 JVM 可能重复，无法标识归属 | value 用「JVM UUID 前缀 + threadId」，全局唯一 |

这与此前 08-13 缓存互斥锁修复「Bug 2」用的是同一套思路，现在 `SimpleRedisLock` 也补上了锁归属校验，分布式锁的释放不再有误删风险。

## 仍存在的遗留问题（未改代码）

1. **TTL 1200 秒过长**：`tryLock(1200)` 给了 20 分钟过期时间，持锁线程崩溃后该用户长时间无法下单。建议缩短到秒杀业务实际耗时的量级，或配合续期机制。
2. **锁粒度只到用户级**：key 为 `lock:order:{userId}`，同一用户抢不同券也互斥、会被误判为「请勿重复点击」。若希望互不阻塞，可细化为 `lock:order:{userId}:{voucherId}`。

## 代码待清理（未改代码，仅提示）

本次改动里有几处调试/误引入的残留，建议清理：

- `VoucherOrderServiceImpl` 第 78 行 `String qu="10o";` 是调试残留，应删除。
- `SimpleRedisLock` 顶部有多个无用 import：`io.lettuce.core.dynamic.annotation.Key`、`org.apache.ibatis.javassist.ClassPath`、`java.util.concurrent.locks.Lock`、`org.springframework.data.redis.core.script.RedisScript`，均未被使用，建议删除。

---

# 优惠券秒杀：异步下单 —— 阻塞队列 + Redis 资格判断

> 日期：2026-08-19

## 背景

此前（08-15 ~ 08-17）的秒杀方案是「同步串行」的：请求进来后，要依次完成「查库存 → 一人一单校验 → 扣库存 → 写订单 → 返回结果」，其中数据库的 `select count`、`update stock`、`insert order` 都发生在请求线程里，响应被拖到「所有 DB 操作完成」才返回。

问题在于：**真正耗时的不是「判断有没有资格」，而是「把订单写进数据库」**。同步模式下，一个请求要等数据库写完订单才给用户响应，导致单次响应时间长、每秒吞吐量上不去，秒杀高峰时数据库成为瓶颈。

## 方案

把下单拆成两段：

1. **同步段（快）**：用 Redis 判断购买资格、扣减 Redis 库存，通过后立即返回订单 id；
2. **异步段（慢）**：把「写数据库」这一重活交给后台的阻塞队列 + 单线程消费者慢慢做，不阻塞请求响应。

即「Redis 判断资格 → 阻塞队列 → 异步保存订单」，用 Redis 的高吞吐承担资格判断，用异步化把慢的 DB 写入从请求链路里摘出来。

## 关键实现

### 1. 用 Lua 脚本在 Redis 里原子判断资格并扣库存

新增 `resources/seckill.lua`，在 Redis 单线程内原子地完成「查库存 + 查购买上限 + 扣库存 + 记购买用户」四步：

```lua
-- 库存 key：seckill:stock:{voucherId}
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1                              -- 库存不足
end
-- 已购买集合 key：seckill:order:{voucherId}
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2                              -- 购买数量已达上限
end
redis.call('incrby', stockKey, -1)        -- 扣 Redis 库存
redis.call('sadd', orderKey, userId)      -- 记录该用户已购买
return 0
```

`seckillServer` 里执行脚本，根据返回值拦截：

```java
Long executeResult = stringRedisTemplate.execute(SECKILL_SCRIPT,
        Collections.emptyList(), voucherId.toString(), userId.toString());
int result = executeResult.intValue();
if (result == 1) return Result.fail("优惠券已售罄");
if (result == 2) return Result.fail("购买数量已达上限");
```

因为整个脚本在 Redis 单线程里跑，`get + sismember + incrby + sadd` 之间不会被其他命令插入，判断和扣减是原子的，天然避免了「多个请求同时通过资格检查」的并发问题。

### 2. 阻塞队列 + 单线程消费者异步写库

```java
// 阻塞队列，存放待写入数据库的订单
public BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

// 单线程池，专门消费队列里的订单
public static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

@PostConstruct
private void init() {
    SECKILL_ORDER_EXECUTOR.submit(new voucherOrderHandler());
}
```

消费者 `voucherOrderHandler` 是一个内部类 `Runnable`，无限循环 `take()` 取订单、调用 `handleVoucherOrder` 写库。

### 3. 同步入口只做「轻」的事，然后立刻返回

```java
long orderId = redisIdWorker.nextId("order:");
VoucherOrder voucherOrder = new VoucherOrder();
voucherOrder.setId(orderId);
voucherOrder.setUserId(userId);
voucherOrder.setVoucherId(voucherId);
orderTasks.add(voucherOrder);          // 投递到队列，不等写库完成
return Result.ok(orderId);             // 立即返回订单 id
```

订单 id 用 `RedisIdWorker.nextId("order:")` 提前生成，响应只返回一个订单号，真正的落库由后台线程完成。

### 4. 后台写库沿用「Redisson 锁 + 事务」兜底

`handleVoucherOrder` 里，对同一用户加 `Redisson` 分布式锁（`lock:order:{userId}`），再用代理对象调用事务方法 `createVoucherOrder` 完成「一人一单校验 + 乐观锁扣 DB 库存 + 写订单」。这一层保留了前几版防超卖的所有手段，只是从「请求线程」挪到了「后台线程」执行。

## 收益

- **缩短响应时间**：请求链路里只剩「Lua 判断 + 生成订单号 + 入队」，不再等数据库写入，单次响应显著变快。
- **提高每秒吞吐**：资格判断交给 Redis（快），DB 写入异步化，单位时间能扛住更多请求。
- **削峰**：阻塞队列充当缓冲，把瞬间涌入的请求「削平」，后台单线程按自己的节奏消费，避免数据库被瞬时打爆。

## qps测试变化（1000个不同用户相同请求）

- 未使用异步秒杀方案![image-20260819154924259](C:\Users\endless\AppData\Roaming\Typora\typora-user-images\image-20260819154924259.png)

- 使用redis优化购买资格查询![image-20260819155050469](C:\Users\endless\AppData\Roaming\Typora\typora-user-images\image-20260819155050469.png)

- 使用阻塞队列实现异步下单

  ![image-20260819155129283](C:\Users\endless\AppData\Roaming\Typora\typora-user-images\image-20260819155129283.png)

  

## 遗留问题与建议（未改代码）

1. **`proxy` 字段存在线程可见性隐患**：`proxy = (IVoucherOrderService) AopContext.currentProxy();` 在请求线程里赋值，却由后台消费线程读取。虽然 `AopContext.currentProxy()` 返回的代理对象对同一个 bean 是稳定的，但普通字段跨线程写读没有 `volatile`/锁保护，理论上消费线程可能读到 `null` 触发 NPE。→ 建议把代理对象改成**一次性注入**（如构造器注入 `@Autowired IVoucherOrderService self`，或在 `@PostConstruct init()` 里设置一次），并声明为 `final`/`volatile`，不要在每次请求里重复赋值。

2. **本地内存队列丢单风险**：订单先返回成功、后落库，队列在 JVM 内存里。一旦进程崩溃或重启，队列里尚未消费的订单会直接丢失——用户已拿到 `orderId`、数据库却没有这笔订单。→ 建议用可靠消息队列（RocketMQ / Kafka）替代本地 `ArrayBlockingQueue`，消息落盘 + 消费确认，保证「下单成功」的消息不丢。

3. **`orderTasks.add()` 队列满会抛异常**：`add` 在队列满时抛 `IllegalStateException`，且发生在返回成功响应之前，会把本该成功的请求变成 500。队列容量 1024×1024 虽大，高峰时仍可能触顶。→ 建议改用 `put`（队列满则阻塞等待，天然限流）或 `offer` + 失败兜底，并配合上游限流。

4. **Redis 与 DB 库存双写不一致**：Lua 扣的是 Redis 库存（`seckill:stock:{voucherId}`），异步线程 `createVoucherOrder` 又对数据库做了一次 `stock = stock - 1 where stock > 0`。若后台扣减失败（DB 库存不足、事务回滚），Redis 已扣、DB 未扣，两边库存会漂移。→ 建议明确「Redis 为准、DB 兜底/对账」的一致性策略：后台写库失败要有重试或补偿（回滚 Redis 库存），必要时定期对账修正。

5. **拿锁失败静默丢单**：`handleVoucherOrder` 里 `lock.tryLock()` 失败直接 `return`，但该订单已经 `take()` 出队、不会重新入队，等于用户拿到订单号却没真正下单成功。→ 建议锁失败时把订单重新放回队列（`offer` 回队）或记录到失败表等待补偿。

6. **异常日志丢失堆栈**：`catch (Exception e) { log.error("订单处理异常 "); }` 没把 `e` 打出来，出了错很难定位。→ 建议改成 `log.error("订单处理异常", e)`。

7. **线程池与队列没有优雅停机**：单线程消费者是 `while(true)` 死循环，`SECKILL_ORDER_EXECUTOR` 也没有 `@PreDestroy` 关闭，应用关闭时队列里剩余订单处理不完即丢。→ 建议加 `@PreDestroy`：停止接收新任务、关闭线程池，并把队列里剩余订单落盘或走补偿。

---

# 优惠券秒杀：异步下单 —— 阻塞队列升级为 Redis Stream 消费者组

> 日期：2026-08-19

## 背景

上一版（阻塞队列方案）虽然把 DB 写入异步化了，但 `ArrayBlockingQueue` 是 **JVM 本地内存**，存在三个痛点：

1. **消息接收不到的丢失**：`seckill.lua` 判断资格、扣 Redis 库存成功后，回到 Java 里才 `orderTasks.add(voucherOrder)`。这两步之间不是原子的——如果进程在「Lua 成功、入队前」这段时间崩溃，或队列已满导致 `add` 抛异常，库存已扣、订单却没入队，消息凭空消失。
2. **消息处理不了的丢失**：消费线程 `take()` 取出订单后，`handleVoucherOrder` 一旦抛异常，订单已经出队、不会重新入队，异常消息直接丢失。
3. **消费能力受限**：单线程 + 本地队列，消费速度受限于单个 JVM 进程，无法横向扩展；且 `take()` 是本地内存阻塞，无法跨进程共享。

## 方案

改用 **Redis Stream 的消费者组（Consumer Group）模式**，让「消息的生产」和「资格判断」在同一次 Redis 原子操作里完成，消费端用「阻塞读 + ack 确认 + pending-list 兜底」保证消息不丢、可重试，同时消费者组天然支持多消费者并行扩展。

整体链路变为：

```
seckillServer → Lua（判资格 + 扣库存 + XADD 发消息，原子）→ 立即返回 orderId
                                                          ↘ stream.orders（Redis Stream）
消费者组 g1（c1, c2, …）阻塞读「>」→ 写库 → XACK 确认
处理异常不 XACK → 进入 pending-list → handlePendingList 重读重处理
```

## 关键实现

### 1. Lua 脚本原子地「发消息」，消除「扣了库存却没入队」的窗口

`seckill.lua` 在扣减库存、记录购买用户之后，**同一条 Lua 脚本里** `XADD` 把订单消息写入 Stream：

```lua
redis.call('incrby', stockKey, -1)   -- 扣 Redis 库存
redis.call('sadd', orderKey, userId) -- 记录已购买用户
-- 将订单信息发送到消息队列，与上面两步同一次原子执行
redis.call('XADD', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
```

`seckillServer` 把 `orderId` 作为第三个参数传给脚本，脚本通过后直接返回，Java 侧不再负责入队：

```java
long orderId = redisIdWorker.nextId("order:");
Long executeResult = stringRedisTemplate.execute(SECKILL_SCRIPT,
        Collections.emptyList(),
        voucherId.toString(), userId.toString(), String.valueOf(orderId));
...
return Result.ok(orderId);   // 消息已经在 Lua 里原子写入 stream 了
```

Lua 脚本在 Redis 单线程里整体原子执行，「扣库存 + 记用户 + 发消息」要么全成功、要么全失败，彻底消除了「扣了库存但消息没入队」的丢失窗口。这是本次改造解决**消息接收不到的丢失**的关键。

### 2. 消费端用消费者组「阻塞读 + 显式 ack」

消费线程不再 `take()` 本地队列，而是用 `xreadgroup` 阻塞读 Stream：

```java
List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
        Consumer.from("g1", "c1"),                                          // 组 g1、消费者 c1
        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2000)), // BLOCK 2000，无消息时阻塞等待
        StreamOffset.create(queueName, ReadOffset.lastConsumed())           // 等价于 ">"，只读未投递的新消息
);
```

- `ReadOffset.lastConsumed()` 对应 XREADGROUP 里的 `>`，只读「尚未投递给本组任何消费者」的新消息，配合消费者组，同一个消息不会重复投递给组内不同消费者。
- `block(2000)` 让消费者在没有消息时阻塞 2 秒等待，而不是空轮询（`take` 式忙等），省 CPU。
- 处理成功后显式 `XACK` 确认：

```java
handleVoucherOrder(voucherOrder);
stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
```

消息只有被 `XACK` 后才会从该消费者的 pending-list 移除，才算真正消费完成。

### 3. 处理失败 → pending-list 兜底重试

主循环 `catch` 到异常后不 ack，消息自动进入该消费者的 **pending-list（PEL）**，然后调用 `handlePendingList()` 把 pending 消息重新读出来处理：

```java
// 读 pending-list：从 "0" 开始读该消费者尚未 ack 的消息
List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
        Consumer.from("g1", "c1"),
        StreamReadOptions.empty().count(1),
        StreamOffset.create(queueName, ReadOffset.from("0"))   // 读 PEL 里待处理的消息
);
...
handleVoucherOrder(voucherOrder);   // 重新处理
```

因为「处理失败的消息不 ack 就仍留在 pending-list」，异常订单不会像阻塞队列那样「出队即丢」，而是被再次读出来重试，直到处理成功。这是本次改造解决**消息不能正常处理后丢失**的关键。

## 解决了什么

| 上一版（阻塞队列）的痛点 | 本次（Redis Stream 消费者组）如何解决 |
|--------------------------|----------------------------------------|
| Lua 成功到 Java 入队之间有丢失窗口（接收不到的丢失） | Lua 里原子 `XADD`，扣库存与发消息同一次原子执行，无窗口 |
| `take()` 后异常消息出队即丢（处理不了的丢失） | 处理失败不 ack，消息留在 pending-list，被重新读取重试 |
| 单线程 + 本地内存队列，消费慢、不可扩展 | `BLOCK` 阻塞读省去空轮询；消费者组支持多消费者并行 |
| 队列在 JVM 内存，进程崩溃即丢 | 消息落在 Redis（可持久化），不随进程消失 |

## qps测试

![image-20260819210955743](C:\Users\endless\AppData\Roaming\Typora\typora-user-images\image-20260819210955743.png)

## 遗留问题与建议（未改代码）

1. **pending-list 处理成功后没有 `XACK`**：`handlePendingList()` 里 `handleVoucherOrder` 成功执行后，并没有调用 `acknowledge` 把消息从 PEL 移除。结果：消息处理成功了，却仍留在 pending-list，下次一旦有异常触发 `handlePendingList`，会把它**再次读出来重复处理**，有重复下单风险。→ 建议在 pending-list 处理成功后也补上 `acknowledge(queueName, "g1", record.getId())`。

2. **业务失败被当成成功 ack，订单仍会静默丢失**：主循环里 `acknowledge` 是无条件执行的，而 `handleVoucherOrder` 内部「锁失败（请勿重复点击）」「一人一单超限」「DB 库存不足」都是 `log.error` + `return`（不抛异常）。这样消息处理「没成功」却照样被 ack 掉，既不会进 pending-list 也不会重试，用户拿到了 `orderId` 但数据库没有订单。→ 建议让 `handleVoucherOrder`/`createVoucherOrder` 返回成功与否，只有真正落库成功才 `XACK`；业务失败要么抛出异常让它进 pending-list，要么记录到失败表显式补偿。

3. **`proxy` 字段的线程可见性隐患仍在**：`proxy = (IVoucherOrderService) AopContext.currentProxy();` 仍在请求线程赋值、消费线程读取，无 `volatile` 保护。虽然 `AopContext.currentProxy()` 返回的代理对同一 bean 稳定，但理论上消费线程可能读到 `null` NPE。→ 建议改成一次性注入（构造器注入或 `@PostConstruct` 里设置），声明 `final`/`volatile`。

4. **仍只有一个消费者 c1，并行消费没真正打开**：消费者组模式虽支持多消费者并行，但 `SECKILL_ORDER_EXECUTOR` 仍是 `newSingleThreadExecutor()`、只提交了一个 c1，消费速度仍是单线程。「加快消费」目前主要来自阻塞读省空轮询 + pending 兜底。→ 若想真正并行消费，可提交多个消费者（c1/c2/c3…，每个线程一个 `Consumer.from("g1", "c{n}")`），让组内多消费者分摊消息。

5. **Redis 与 DB 库存双写一致性仍未处理**：Lua 扣 Redis 库存、异步线程又对 DB 做 `stock = stock - 1 where stock > 0`，后台失败时两边会漂移。→ 建议明确「Redis 为准、DB 兜底/对账」的一致性策略（失败回滚 Redis 库存或定期对账）。

6. **`import lombok.Value;` 是无用导入**：第 12 行 `import lombok.Value;` 未被使用，建议删除。

7. **`handlePendingList` 重试间隔偏小且无上限**：重试仅 `Thread.sleep(20)`，若某条消息持续处理失败（如 DB 异常），会以 20ms 间隔无限重试，打爆日志和数据库。→ 建议加最大重试次数 / 退避策略，超过后转人工死信表。

---

# 博客点赞：Redis ZSet 存点赞用户，一人一赞 + 点赞排行

> 日期：2026-08-20

## 背景

`BlogServiceImpl` 此前只有 `queryHotBlog`（按 `liked` 倒序分页查热点博客）和 `queryBlogById`（查单个博客），点赞相关的 `BlogController.likeBlog` 里只有一行被注释掉的 `setSql("liked = liked + 1")`——既没记录「谁点了赞」，也没法判断「当前用户是否点过赞」，更没有点赞列表。

本次用 Redis 的 ZSet 存「每个博客的点赞用户集合」，补齐点赞闭环：

1. 查询博客信息 + 热点博客信息（注入作者昵称/头像 + 当前用户是否已赞）；
2. 点赞 / 取消点赞（一人一赞）；
3. 点赞排行列表（Top5）；
4. 详情页点赞数量展示。

点赞用户 id 放 Redis，判断是否已赞、取点赞列表都走 Redis，只有最后回查用户昵称/头像时才访问数据库。

## 方案

### 1. Redis 数据结构：ZSet 存点赞用户

- key：`blog:liked:{blogId}`（`RedisConstants.BLOG_LIKED_KEY = "blog:liked:"`）
- member：点赞用户 `userId`（存成字符串）
- score：点赞时间 `System.currentTimeMillis()`

选 ZSet 而不是 Set 的原因：既要 `score()` 判断成员是否存在（一人一赞）、又要按 score 排序取 TopN（点赞排行）、还能靠 member 唯一性天然去重，一个结构同时满足三个需求。

### 2. 点赞 / 取消点赞（一人一赞）

`likeBlog(id)` 先用 `zscore` 判断当前用户是否已在集合中——在则取消赞（DB `liked - 1` + zset `remove`），不在则点赞（DB `liked + 1` + zset `add`）：

```java
String key = RedisConstants.BLOG_LIKED_KEY + id;
Double isMember = stringRedisTemplate.opsForZSet().score(key, userId.toString());
if (isMember != null) {
    boolean cancelLike = update().setSql("liked = liked - 1").eq("id", id).update();
    if (cancelLike) stringRedisTemplate.opsForZSet().remove(key, userId.toString());
} else {
    boolean like = update().setSql("liked = liked + 1").eq("id", id).update();
    if (like) stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
}
```

点赞数量仍持久化在数据库 `tb_blog.liked` 字段，用 `setSql("liked = liked ± 1")` 原子更新，详情页展示这个值。

### 3. 判断当前用户是否已赞（一人一赞）

`isBlogLiked(blog)`：查当前登录用户 `userId` 在 `blog:liked:{id}` 里的 `score`，非 null 说明点过赞，置 `blog.isLike = true`。这个状态在 `queryHotBlog` 和 `queryBlogById` 里都会注入，前端据此渲染红心。

```java
Double isMember = stringRedisTemplate.opsForZSet().score(key, userId.toString());
if (isMember != null) {
    blog.setIsLike(true);
}
```

### 4. 点赞排行列表 Top5

`queryBlogLikeList(id)`：`zrange(key, 0, 4)` 取前 5 个 userId，再 `listByIds` 回查用户，转成 `UserDTO`（只含 id / nickName / icon，脱敏）返回。

```java
Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
List<Long> ids = top5.stream().map(Long::parseLong).collect(Collectors.toList());
List<UserDTO> dtos = userService.listByIds(ids).stream()
        .map(u -> BeanUtil.copyProperties(u, UserDTO.class)).collect(Collectors.toList());
```

### 5. 查询博客 / 热点博客并注入用户信息与点赞状态

`queryBlogById` / `queryHotBlog` 查出 Blog 后，统一走 `queryBlogUser(blog)`（注入作者昵称 + 头像）和 `isBlogLiked(blog)`（注入是否已赞），让列表页和详情页都能拿到作者信息和点赞状态。

## 解决了什么

| 之前 | 现在 |
|------|------|
| 点赞只有被注释的 `liked+1`，不知道谁点过赞 | ZSet 记录每个博客的点赞用户，可判断、可排行 |
| 无法判断当前用户是否已赞 | `score()` 判断成员，一人一赞 |
| 详情页点赞数只能看到静态值 | `liked` 实时 +1/-1，详情页展示最新点赞数 |
| 查点赞列表要扫库 | 点赞列表走 Redis ZSet，DB 只按 id 回查用户昵称头像 |
| 热点博客/详情不带作者信息和点赞状态 | 统一注入作者昵称、头像、是否已赞 |

## 遗留问题与建议（未改代码）

1. **`queryBlogLikeList` 空集合判断写反**：`if (likeListTop5 != null || likeListTop5.isEmpty())` 应为 `== null ||`。当前「有数据（非 null）→ 返回空列表」「无数据（null）→ 抛 NPE」，点赞列表永远空、头像也不显示。这是本次功能里最严重的一个 bug。已解决

2. **点赞列表取到的是「最早点赞」而非「最近点赞」**：`likeBlog` 用 `System.currentTimeMillis()` 当 score（越晚越大），但 `queryBlogLikeList` 用 `zrange(key, 0, 4)`（score 从小到大取前 5），拿到的是最早点赞的 5 人。若要「最近点赞 Top5」，应改用 `reverseRange(key, 0, 4)`。

3. **未点赞时 `isLike` 是 null 而非 false**：`isBlogLiked` 只在命中时 `setIsLike(true)`，其余情况不赋值（默认 null）。前端 `v-if="blog.isLike"` 下 null 与 false 行为一致，但语义不清，建议补 `else setIsLike(false)`。已解决

4. **点赞的 Redis 与 DB 双写一致性**：先改 DB `liked`，成功后才改 ZSet；若 Redis 写失败，两边漂移（DB 计数变了、ZSet 没变，一人一赞判断失效）。且 `liked = liked - 1` 没有下限保护，极端情况可能减成负数。→ 建议减操作加 `gt("liked", 0)` 兜底，或用 Lua 脚本把「判断是否已赞 + 加减计数」原子化。

5. **`queryBlogUser` 未判空**：`blog.getUserId()` 对应的 user 若不存在（用户已删），`user.getNickName()` 会 NPE。→ 建议加 null 判断，查不到就跳过或给默认值。

6. **`queryMyBlog` 未注入用户信息与点赞状态**：`/blog/of/me` 直接返回 `page.getRecords()`，没走 `queryBlogUser`/`isBlogLiked`，「我的笔记」列表会缺作者头像和点赞状态。→ 建议与 `queryHotBlog` 统一处理。

7. **`queryHotBlog` 有无效注释残留**：第 54 行 `//records.forEach(this::queryBlogUser(blog));` 是死代码注释，建议删除。

---

# 关注功能 + 共同关注 + 博客 Feed 流推送（推模式）

> 日期：2026-08-20

## 背景

本次围绕「社交关系」新增了三个功能：关注/取关、共同关注、博客推送（Feed 流）。

- **关注关系**用 Redis Set 存「某用户的关注列表」做快速查询，用 MySQL 中间表 `tb_follow` 做持久化；
- **共同关注**用 Redis 的集合交集命令（`SINTER`）实现；
- **博客推送**采用 Feed 流的**推模式**：博主发博客时，把博客 id 推到每个粉丝的「信箱」（Redis ZSet），博客正文仍存 MySQL。用户端 Feed 流的查看功能尚未实现。

## 一、关注 / 取关（FollowServiceImpl.follow）

关注关系同时落在两处：MySQL `tb_follow` 中间表（持久化）+ Redis Set（快速查询）。

- key：`follows:{userId}`
- member：`followUserId`

```java
// 关注
Follow follow = new Follow();
follow.setFollowUserId(followUserId);
follow.setUserId(userId);
boolean isSuccess = save(follow);                 // 1. 先写 MySQL
if (isSuccess) {
    stringRedisTemplate.opsForSet().add(key, followUserId.toString());  // 2. 再写 Redis
}
// 取关
boolean isSuccess = remove(new QueryWrapper<Follow>()
        .eq("user_id", userId).eq("follow_user_id", followUserId));
if (isSuccess) {
    stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
}
```

## 二、判断是否关注（isFollow）

查 `tb_follow` 里是否存在 `(user_id, follow_user_id)` 记录，`count > 0` 即已关注。

## 三、共同关注（followCommons）

对「当前登录用户」和「目标用户」两个关注列表取交集：

```java
String userKey = "follows:" + userId;   // 当前登录用户
String curKey  = "follows:" + userId;   // ← 目标用户（应取参数 id，见下方 bug）
Set<String> set = stringRedisTemplate.opsForSet().intersect(userKey, curKey);
```

交集结果是一批 userId，再 `listByIds` 回查用户、转成 `UserDTO` 返回。核心命令是 Redis 的 `SINTER`（`opsForSet().intersect`）。

## 四、博客推送 —— Feed 流推模式（BlogServiceImpl.saveBlog）

博主发博客时，先把正文存 MySQL，再查出所有粉丝，把博客 id 逐个推进每个粉丝的「信箱」：

- 信箱 key：`feed:{fansId}`（成员 = `blogId`，score = 当前时间戳）
- 用的是 **ZSet**（而非 Set）：member 存博客 id、score 存发布时间，方便后续按时间倒序拉取 Feed

```java
// 1. 存正文到 MySQL
boolean saveSuccess = save(blog);
// 2. 查出粉丝（关注了博主的人）
List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
// 3. 逐个推到粉丝信箱
for (Follow follow : follows) {
    Long fansId = follow.getUserId();
    String key = "feed:" + fansId;
    stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
}
```

推模式把「写扩散」放在博主发博客这一刻完成，粉丝读 Feed 时无需回查所有关注对象，读取端（尚未实现）只需读自己的信箱即可。

## 解决了什么

| 需求 | 实现 |
|------|------|
| 关注 / 取关 | `tb_follow` 持久化 + Redis Set `follows:{userId}` 快速查询 |
| 是否已关注 | 查 `tb_follow` 记录，count>0 |
| 共同关注 | Redis `SINTER` 求两个关注列表交集 |
| 博客推送 | 推模式，发博客时写入每个粉丝的信箱 ZSet，正文存 MySQL |

## 遗留问题与建议（未改代码）

### FollowServiceImpl

1. **【严重】共同关注查错目标用户**：`followCommons` 里 `curKey = "follows:" + userId` 写成了当前用户自己，正确应为 `"follows:" + id`（目标用户）。当前 `intersect(userKey, curKey)` 实际是「当前用户关注列表 ∩ 自己」，永远返回当前用户自己的完整关注列表，共同关注功能等于没生效；方法参数 `id` 完全没被使用。→ 修复：`String curKey = "follows:" + id;`已解决

2. **`copyProperties` 目标类型用实例 getClass()**：`BeanUtil.copyProperties(user, userDTO.getClass())` 依赖 `userDTO` 的运行时类型，虽当前能跑，但不够稳。→ 建议直接写 `BeanUtil.copyProperties(user, UserDTO.class)`。已解决

3. **关注无去重、无唯一索引**：`follow()` 里 `save()` 前不判断是否已关注，`tb_follow` 也没有 `(user_id, follow_user_id)` 唯一索引，重复关注会产生脏数据。→ 建议加唯一索引兜底，或 save 前先查重。

4. **Redis 与 DB 双写不一致**：先写 DB 成功、再写 Redis，Redis 写失败会漂移；取关时 DB `remove` 成功但 Redis `remove` 失败也会不一致。→ 建议关注/取关用 Lua 脚本把判断 + 写入原子化，或至少做补偿。

5. **`isFollow` 数据源与关注列表不一致**：关注列表存 Redis，`isFollow` 却每次都查 DB。→ 建议优先查 Redis（`sismember` / `score`），DB 兜底。

6. **无用 import 残留**：`org.apache.ibatis.annotations.Delete`、`java.util.function.Consumer`、`java.util.function.Function` 未使用，建议删除。

### BlogServiceImpl（Feed 推送）

7. **`queryBlogUser` 未判空**：`user.getNickName()` / `user.getIcon()` 在用户不存在时 NPE（上一篇 08-20 点赞日志 #5 已提，仍未改）。已解决

8. **信箱 key 硬编码**：`"feed:" + fansId` 应改用已定义的 `RedisConstants.FEED_KEY`。

9. **推模式同步循环推送，粉丝多时阻塞请求**：`saveBlog` 里串行 `for` 循环往每个粉丝信箱 `ZADD`，且与 DB `save` 之间无事务；粉丝量大时请求变慢，中途异常会导致「部分粉丝已推、部分未推」。→ 建议异步化 / 分批 / 消息队列；粉丝量级大时考虑拉模式或推拉结合。

10. **Feed 读取端未实现**：`saveBlog` 只做了「写扩散」（推），尚无读取信箱、按时间倒序拉取关注对象的 Feed、并注入作者信息的接口（用户已说明，后续实现）。

---

# Feed 流滚动分页 + 店铺地理位置（GEO）分 type 存储与按距离查询

> 日期：2026-08-21

本次完成三个功能，全部围绕 Redis 的高级能力展开：

1. **滚动分页查看关注博主发布内容**（Feed 流读取端）——用 Redis ZSet「信箱」+ `ZREVRANGEBYSCORE` 实现滚动分页；
2. **店铺地理位置分 type 存储**——用 Redis GEO 按「店铺类型」分组存储店铺坐标；
3. **店铺分类查询并按距离排序**——用 Redis GEO 的 `GEOSEARCH` 按用户位置对某类店铺做半径搜索 + 距离排序。

---

## 一、滚动分页查询关注博主发布内容（Feed 流读取端）

### 背景

上一篇（08-20）实现了 Feed 流的**推模式**：`saveBlog` 在博主发博客时，把博客 id 推进每个粉丝的「信箱」ZSet（`feed:{fansId}`，member=blogId，score=发布时间戳）。但当时只做了「写扩散」，读取端尚未实现。本次补上读取端 `queryBlogOfFollow`，用**滚动分页**从自己的信箱按时间倒序拉取关注对象的博客。

### 数据结构

- 信箱 key：`feed:{userId}`（`RedisConstants.FEED_KEY = "feed:"`）
- member：`blogId`
- score：`System.currentTimeMillis()`（发布时间戳）

选 ZSet 而不是 List/Set：既要按时间**倒序**取（ZSet 的 score 排序），又要支持「时间戳相同」时的**去重翻页**（靠 score + offset 组合），ZSet 天然满足。

### 入口与返回

- 接口：`BlogController.queryBlogOfFollow` → `GET /blog/of/follow?lastId=&offset=`
- 返回 DTO：`ScrollResult`（`list` 结果列表 / `minTime` 下次查询的起始 score / `offset` 下次查询的偏移量）

```java
public class ScrollResult {
    private List<?> list;      // 本页结果
    private Long minTime;      // 下次查询的 max（最小 score）
    private Integer offset;    // 下次查询要跳过的同 score 元素个数
}
```

### 核心查询

```java
Set<ZSetOperations.TypedTuple<String>> blogTuples = stringRedisTemplate.opsForZSet()
        .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
```

- `reverseRangeByScoreWithScores(key, 0, max, offset, count)` 等价于 `ZREVRANGEBYSCORE key max 0 WITHSCORES LIMIT offset count`：取 score 在 `[0, max]` 区间的成员，**按 score 倒序**，先跳过 `offset` 个、再取 `count` 个。
- `max` = 上一页返回的 `minTime`，`offset` = 上一页返回的 `offset`，二者配合实现「滚动翻页 + 同时间戳去重」。

### 解析与回查

```java
// 解析出 blogIds、本次最小 score（下次 max）、下次 offset
for (tuple : blogTuples) {
    blogIds.add(Long.valueOf(tuple.getValue()));
    long currentTime = tuple.getScore().longValue();
    if (currentTime == minTime) os++;          // 相同 score 计数 +1
    else if (currentTime < minTime) { os = 1; minTime = currentTime; }
}
// 按 blogIds 回查 DB，用 ORDER BY FIELD 保证与 ZSet 倒序一致
List<Blog> blogs = query().in("id", blogIds).last("ORDER BY FIELD(id," + idsStr + ")").list();
// 逐条注入作者昵称/头像 + 当前用户点赞状态
for (Blog blog : blogs) { queryBlogUser(blog); isBlogLiked(blog); }
```

`minTime` + `offset` 的意义：当同一毫秒有多条博客时，score 相同，直接按 score 翻页会漏读或重读。`offset` 记录「本页中 score == minTime 的元素个数」，下次查询用 `LIMIT offset count` 跳过它们，保证同 score 的博客不漏不重。

### 解决了什么

| 之前 | 现在 |
|------|------|
| Feed 只有写扩散，没有读取接口 | `queryBlogOfFollow` 从信箱倒序滚动拉取 |
| 分页按 `LIMIT n,m` 无法处理同时间戳 | score + offset 组合去重，滚动分页不漏不重 |
| 拉到的博客没有作者信息和点赞状态 | 逐条注入 `queryBlogUser` + `isBlogLiked` |

---

## 二、店铺地理位置分 type 存储（Redis GEO）

### 背景

按距离查询店铺，若每次都把 DB 里的店铺坐标读出来在 Java 里算距离，量大时很慢。Redis 的 **GEO** 结构底层是 ZSet，提供 `GEOSEARCH` 半径搜索 + 距离计算，把「找附近店铺」这件事交给 Redis 完成。因此先把店铺坐标按「类型」灌入 Redis GEO。

### 实现

`HmDianPingApplicationTests.loadShopGeo()`（测试方法，手动执行做数据预热）：

```java
// 1. 查全部店铺
List<Shop> allShop = shopService.list();
// 2. 按 typeId 分组
Map<Long, List<Shop>> shopByType = allShop.stream()
        .collect(Collectors.groupingBy(Shop::getTypeId));
// 3. 每种类型一个 GEO key，member=shopId，坐标=Point(x,y)
for (Map.Entry<Long, List<Shop>> entry : shopByType.entrySet()) {
    String key = RedisConstants.SHOP_GEO_KEY + entry.getKey();   // shop:geo:{typeId}
    List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
    for (Shop shop : entry.getValue()) {
        locations.add(new RedisGeoCommands.GeoLocation<>(
                shop.getId().toString(), new Point(shop.getX(), shop.getY())));
    }
    stringRedisTemplate.opsForGeo().add(key, locations);          // 批量 GEOADD
}
```

- key：`shop:geo:{typeId}`（`RedisConstants.SHOP_GEO_KEY = "shop:geo:"`）
- member：店铺 id（字符串）
- 坐标：`Point(x, y)`（x 经度、y 纬度）
- 「分 type 存储」体现在 key 上：每个店铺类型独立一个 GEO 集合，查询某类店铺时只扫对应的 key。

---

## 三、店铺分类查询并按距离排序（queryShopByType）

### 入口

`ShopController.queryShopByType` → `GET /shop/of/type?typeId=&current=&x=&y=`，`x`/`y` 为用户当前坐标（可为空）。

### 实现（ShopServiceImpl.queryShopByType）

分两种情况：

**情况 1：拿不到用户坐标（x/y 为 null）** —— 退化为普通分页查询，不带距离：

```java
Page<Shop> shops = query().eq("type_id", typeId)
        .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
return Result.ok(shops.getRecords());
```

**情况 2：有坐标** —— 走 Redis GEO 半径搜索 + 距离排序：

```java
String key = RedisConstants.SHOP_GEO_KEY + typeId;                 // shop:geo:{typeId}
int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;      // 跳过条数
int end  = current * SystemConstants.DEFAULT_PAGE_SIZE;            // 取到第几条

GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
        key,
        GeoReference.fromCoordinate(x, y),                          // 圆心 = 用户坐标
        new Distance(5000),                                         // 半径 5km
        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                .includeDistance().limit(end)                       // 附带距离 + 最多取 end 条
);
```

- `opsForGeo().search(...)` 对应 Redis `GEOSEARCH`，结果**已按距离升序**排序，并附带了每家的距离。
- `.limit(end)` 只取前 `end` 条（距离最近的 end 家），`includeDistance()` 让结果里带上 `Distance`。

随后解析、分页、回查：

```java
List<GeoResult<...>> shopList = results.getContent();
if (from >= shopList.size()) return Result.ok(Collections.emptyList());

Map<String, Distance> shopDisMap = new ConcurrentHashMap<>(shopList.size());
List<String> shopIds = new ArrayList<>(shopList.size());
shopList.stream().skip(from).forEach(geoInfo -> {
    String shopId = geoInfo.getContent().getName();
    shopIds.add(shopId);
    shopDisMap.put(shopId, geoInfo.getDistance());                 // 收集每个 shop 的距离
});
// 按 shopIds 回查 DB，ORDER BY FIELD 保证与 GEO 排序一致
String idsStr = StrUtil.join(",", shopIds);
List<Shop> shops = query().in("id", shopIds).last("ORDER BY FIELD(id," + idsStr + ")").list();
// 把距离写回每个 shop（Shop.distance 是 @TableField(exist=false) 的临时字段）
shops.forEach(shop -> shop.setDistance(shopDisMap.get(shop.getId().toString()).getValue()));
return Result.ok(shops);
```

- GEO 只返回「店铺 id + 距离」，真正的店铺详情仍从 DB 按 id 回查；`ORDER BY FIELD` 保证回查结果顺序与 GEO 的距离排序一致。
- 距离通过 `Shop` 上 `@TableField(exist = false)` 的 `distance` 字段透出给前端，不落库。

### 解决了什么

| 之前 | 现在 |
|------|------|
| 按类型查店铺只能翻页，无法「按距离排序」 | GEO 半径搜索 + 距离升序，返回最近的店铺 |
| 算距离要在应用层遍历坐标 | 距离计算交给 Redis GEO，省去应用层计算 |
| 拿不到坐标时接口会异常 | 坐标为空自动退化为普通分页查询 |

---

## 遗留问题与建议（未改代码）

### Feed 流滚动分页（queryBlogOfFollow）

1. **【较明显】`count` 硬编码为 2**：`reverseRangeByScoreWithScores(key, 0, max, offset, 2)` 每次只取 2 条，滚动翻页一次只有 2 条内容。这应是调试/课程演示留下的值。→ 建议改为分页大小常量（如 `SystemConstants.MAX_PAGE_SIZE`=10，或为 Feed 单设常量），与前端滚动加载的期望对齐。

2. **【边界 bug 隐患】`minTime` 初始值用 `max` 而非 `0`**：解析循环里 `long minTime = max; int os = 1;`。标准做法应 `minTime = 0`（或 `Long.MIN_VALUE`）。当首页第一页拿到的首条 score 恰好等于传入的 `max`（例如前端把 `lastId` 传成一个真实时间戳、且该时间戳下有多条博客），会误走 `if (currentTime == minTime) os++`，把「上一页的边界 score」当成「本页最小 score」计数，导致下一次查询 `offset` 偏大、跳过不该跳过的博客。正常翻页（首条 score < max）时逻辑恰好正确，所以平时不易暴露。→ 建议改为 `long minTime = 0;`。

3. **空结果返回不一致**：`queryBlogOfFollow` 在信箱为空时返回 `Result.ok()`（`data` 为 null），而其它查询返回 `Result.ok(Collections.emptyList())`。前端若对 `data` 直接遍历会 NPE。→ 建议统一返回空 `ScrollResult` 或空列表。

4. **同毫秒大量博客时 offset 机制可能重读/漏读**：score 是毫秒时间戳，`count=2` 又很小。若同一毫秒内产生的博客数超过一页大小，`offset` 只统计了「本页」里同 score 的个数（而非总数），下一页仍会从「跳过 offset 个」开始，可能重复返回上一页已读的内容。这是「纯 score 游标」滚动分页的固有局限。→ 建议：score 改用「时间戳 + 单调序号」保证唯一，或用 `(score, blogId)` 复合游标；至少把 `count` 调到合理分页大小以降低同页撞车概率。

5. **N+1 查询**：每页对每条博客循环调 `queryBlogUser`（查 `tb_user`）和 `isBlogLiked`（查 Redis `zscore`）。一页 10 条就是 10 次 user 查询。→ 建议批量：`listByIds` 一次性取本页所有作者、再回填，点赞状态也可考虑 `pipeline` 批量 `zscore`。

### GEO 分 type 存储（loadShopGeo）

6. **GEO 数据是测试方法手动预热，与业务写入脱节**：`loadShopGeo()` 在 `@Test` 里，需人工执行；`saveShop`（新增店铺）和 `update`（更新店铺）都没有同步写 GEO。结果：新增/改过坐标的店铺不会出现在「附近」查询里，GEO 数据会越来越旧。→ 建议把 GEO 写入整合进 `saveShop`/`update` 流程（新增/更新时同步 `GEOADD`/`GEOZREM`），或加一个可重复执行的初始化任务/监听。

7. **坐标未判空**：`new Point(shop.getX(), shop.getY())` 在 x/y 为 null 时会抛 `IllegalArgumentException`/NPE，整批导入失败。→ 建议导入前过滤掉坐标为空的店铺。

8. **`testSaveRedisData` 硬编码 14**：`for (int i = 1; i <= 14; i++)` 假定店铺只有 14 家，店铺数量变化后需手改。→ 建议改为遍历 `shopService.list()` 或按真实数量循环。

### 分类 + 距离查询（queryShopByType）

9. **搜索半径 5km 硬编码**：`new Distance(5000)` 写死 5 公里，超出范围的店铺被直接排除。→ 建议抽成常量或配置（`RedisConstants.SHOP_SEARCH_RADIUS` / `application.yaml`），并考虑让前端传入。

10. **分页用 `limit(end)` + Java `skip(from)`，跨页重复拉取**：每页都让 Redis 返回「前 `end` 条」，再在 Java 里 `skip(from)` 丢掉前面的。翻到第 3 页时实际拉了 3 页的数据。数据量小没问题，量大是浪费。→ 建议：升级到 Redis 6.2+ 的 `GEOSEARCH`（Spring Data Redis 的 `GeoSearchCommandArgs` 支持 `limit` 但旧版无 offset，可考虑 `GEOSEARCH ... WITHCOORD` + 客户端截断，或接受此近似）；或按业务量评估是否值得优化。

11. **`shopDisMap.get(...)` 未判空**：`shop.setDistance(shopDisMap.get(shop.getId().toString()).getValue())` 若某 shop 在 DB 已被删、但 GEO 里残留，回查时该 id 查不到，`shops` 里少一条，不会走到这一行；但若 `shopIds` 与回查结果顺序错位或 key 拼写不一致，则可能 NPE。→ 建议 `get` 后判空再 `setDistance`，避免残留脏数据导致整页 500。

12. **`ConcurrentHashMap` 用于单线程流式遍历**：`shopDisMap` 用 `ConcurrentHashMap`，但 `shopList.stream().skip(from).forEach` 是单线程串行，无并发场景。→ 建议改用普通 `HashMap`，语义更清晰、也避免不必要的并发开销。

---

## 总结

| 功能 | 核心数据结构/命令 | 关键文件 |
|------|------------------|---------|
| Feed 流滚动分页 | ZSet + `ZREVRANGEBYSCORE`（score+offset 游标） | `BlogServiceImpl.queryBlogOfFollow`、`ScrollResult` |
| 店铺坐标分 type 存储 | GEO + `GEOADD`，key=`shop:geo:{typeId}` | `HmDianPingApplicationTests.loadShopGeo` |
| 分类 + 按距离查询 | GEO + `GEOSEARCH`（半径 + 距离排序） | `ShopServiceImpl.queryShopByType` |

三者一脉相承：GEO 分 type 存储（二）是数据准备，按距离查询（三）是消费；Feed 流（一）则复用了 ZSet 的 score 排序做滚动分页，与 GEO 底层同源（GEO 本质也是 ZSet）。

---

# 用户签到：Redis Bitmap 存签到记录 + BITFIELD 统计连续签到天数

> 日期：2026-08-22

本次实现两个功能：

1. **用户签到记录**——用 Redis 的 **Bitmap** 数据结构记录某用户某月的每日签到情况；
2. **连续签到天数统计**——用 Redis 的 **BITFIELD** 命令一次性取出当月签到位图，再在 Java 里从「今天」往回数连续 `1` 的个数。

入口：`UserController` → `POST /user/sign`、`GET /user/sign/count`，逻辑在 `UserServiceImpl.sign()` / `signCount()`。

---

## 一、为什么用 Bitmap 而不是 Set / 字符串

「用户签到」本质是「某用户在某月每一天的一个布尔状态（签 / 没签）」，天然是一串 0/1。用普通 Set 存「已签到的日期」也能做，但：

- 一个月最多 31 天，用 Bitmap 一个 key 最多占 31 bit ≈ 4 字节；
- 用一个 Set 存 31 个日期字符串，光 member 的内存开销就远超 4 字节，还得存年月等冗余信息。

所以选 **Bitmap**：1 个 bit 表示一天，`setBit` 置位即签到，内存极小、命令原子。

### 数据结构

- key：`sign:{userId}:{yyyyMM}`（`RedisConstants.USER_SIGN_KEY = "sign:"`）
- 第 N 天的签到状态存在第 `N-1` 位（bit 从 0 开始编号，1 号 → offset 0，2 号 → offset 1，…… 31 号 → offset 30）

按「用户 + 年月」分 key，跨月自动换新 key，避免单个 key 无限增长。

---

## 二、签到记录（sign）

```java
LocalDateTime now = LocalDateTime.now();
String yearAndMonth = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));   // 形如 :202608
int day = now.getDayOfMonth();                                              // 今天是几号（1~31）
String key = RedisConstants.USER_SIGN_KEY + userId + yearAndMonth;          // sign:{userId}:202608
stringRedisTemplate.opsForValue().setBit(key, day - 1, true);               // 第 day 天 → offset day-1 置 1
```

- `setBit(key, offset, true)` 对应 Redis `SETBIT key offset 1`，把指定 offset 的 bit 置为 1。
- offset 用 `day - 1`：1 号存在第 0 位，保证「今天」恰好落在位图的第 `day-1` 位，为后面的连续统计埋下对齐的伏笔。
- `setBit` 天然幂等：同一位置重复置 1 不改变结果，多次点击签到不会重复计数（Bitmap 自动去重）。

---

## 三、连续签到天数统计（signCount）

核心思路：用 `BITFIELD` 一次性把「当月从第 1 天到今天」这 `day` 位读成一个无符号整数，再在 Java 里从最低位（今天）往高位逐个判断是否连续为 1。

### 1. BITFIELD 取出当月位图

```java
String key = RedisConstants.USER_SIGN_KEY + userId + yearAndMonth;
// 等价于：BITFIELD sign:{userId}:202608 GET u22 0   （假设今天 22 号）
List<Long> results = stringRedisTemplate.opsForValue().bitField(
        key,
        BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0)
);
Long recordBitmap = results.get(0);                 // 取第一个子命令的返回值
if (recordBitmap == null || recordBitmap == 0) return Result.ok(0);
```

- `BitFieldType.unsigned(day)`：按「无符号整数」取 `day` 位（如 22 号取 22 位）。
- `valueAt(0)`：从 offset 0 开始取，即从 1 号到今天的完整位图。

### 2. 从最低位往回数连续 1

```java
int count = 0;
while ((recordBitmap & 1) != 0) {   // 取最低位：是 1 则今天签了
    count++;
    recordBitmap >>>= 1;             // 无符号右移一位，继续看昨天
}
return Result.ok(count);
```

### 为什么「最低位 = 今天」

这是本功能最巧的一点，也是容易绕晕的地方。Redis `BITFIELD GET uN 0` 的语义是：**offset 0 是最高位（MSB），offset N-1 是最低位（LSB）**。

而签到时，1 号存在 offset 0、今天（`day` 号）存在 offset `day-1`。所以读出的这个 `day` 位整数里：

- **最低位（LSB）对应 offset `day-1`，恰好就是「今天」**；
- 次低位对应昨天，依次类推。

于是从 LSB 开始 `& 1` 判断、再 `>>>1` 右移，就是**从今天往过去一天天数连续签到**，正好是「连续签到天数」的定义。

举个例子（今天是 4 号，取 `u4`）：

| 日期 | 1 号 | 2 号 | 3 号 | 4 号(今天) |
|------|-----|-----|-----|-----|
| offset | 0 | 1 | 2 | 3 |
| 签到状态 | 0 | 1 | 1 | 1 |

位图读出的整数 = `0111` = 7。循环：`7&1=1`→count=1，`7>>>1=3`；`3&1=1`→count=2，`3>>>1=1`；`1&1=1`→count=3，`1>>>1=0` 结束。结果连续 3 天，正确。

---

## 解决了什么

| 之前 | 现在 |
|------|------|
| 无签到功能 | Bitmap 记录每月每日签到，内存极小、原子、幂等去重 |
| 无法统计连续签到 | BITFIELD 一次读位图 + 从 LSB 回数连续 1，得到连续天数 |
| 用 Set 存签到日期内存浪费 | Bitmap 每月最多 31bit≈4 字节 |

---

## 遗留问题与建议（未改代码）

1. **重复签到无提示**：`sign()` 忽略了 `setBit` 的返回值（该命令返回「设置前这一位是 0 还是 1」）。当前 Bitmap 幂等、不会重复计数，所以不算错，但拿不到「今天是否已签到」的信息，前端无法提示「已签到」。→ 建议用 `Boolean already = stringRedisTemplate.opsForValue().setBit(key, day-1, true);` 接住返回值，`already == true` 表示今天已签过，可据此提示或拦截。

2. **签到 key 无过期时间**：`setBit` 没设 TTL，按月一个 key 会永久留存。数据量不大（每用户每月几字节），但用户规模大、时间长了会累积。→ 建议给 key 设一个较长 TTL（如 1~2 年，`stringRedisTemplate.expire(key, ...)`），或按月做离线归档清理。

3. **跨月连续签到会断**：key 按「年月」切分，若用户在 7 月 31 号和 8 月 1 号都签到，按业务应是「连续 2 天」，但 8 月 1 号统计时只读 8 月的位图（当天只有 1 位），结果只能是 0 或 1，把上月底的连续记录丢掉了。→ 若产品要求跨月连续，需在统计时额外查上月末位图续接（拼上上个月的最后若干位），或换用「自起始日连续的全局 key」方案；黑马课程默认接受此局限。

4. **`yearAndMonth` 格式串含前导冒号**：`DateTimeFormatter.ofPattern(":yyyyMM")` 把分隔符 `:` 写进了格式串，生成 `:202608`，再与 `USER_SIGN_KEY`（`sign:`）和 userId 拼成 `sign:{userId}:202608`。功能正确，但分隔符藏在格式串里可读性差、易踩坑。→ 建议格式串只保留 `"yyyyMM"`，拼接时显式写 `RedisConstants.USER_SIGN_KEY + userId + ":" + yearAndMonth`。

5. **今天未签到返回 0**：`signCount()` 从最低位（今天）开始数，今天没签就直接返回 0，即使昨天及之前连续签了 10 天。若产品希望「今天还没签时，展示截至昨天的连续天数」，需要从「今天有没有签」做分支：今天签了从 LSB 数，今天没签则从次低位（昨天）开始数。当前实现返回 0 是黑马课程默认语义，是否要改取决于产品定义。

---

## 总结

| 功能 | 核心命令 / 数据结构 | 关键点 |
|------|--------------------|--------|
| 签到记录 | `SETBIT`（Bitmap） | 第 N 天 → offset N-1，按月分 key，幂等去重 |
| 连续签到统计 | `BITFIELD GET u{day} 0` + Java 位运算 | LSB=今天，`&1` 判断 + `>>>1` 右移从今往回数 |

本功能整体实现规范、思路正确，尤其「offset 0 是 MSB、LSB 恰对应今天」这个位序对齐，是连续签到统计正确性的关键。主要待改进点是重复签到提示、key TTL 与跨月连续这三个工程化细节。
