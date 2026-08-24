# 黑马点评 (hm-dianping)

仿大众点评的前后端分离项目，重点演示 Redis 在真实业务中的落地：缓存、分布式锁、秒杀、GEO、Bitmap、Stream 消息队列等。

## 项目描述

围绕 Redis 展开的点评类应用，涵盖 **短信登录、商户缓存、优惠券秒杀、附近商户、用户签到、好友关注、达人探店** 等模块。核心通过 Redis 缓存与分布式锁解决集群下的性能与并发问题：以 Redis 替换 HttpSession 解决 Session 共享，以缓存 + 分布式锁支撑高并发读与「一人一单」秒杀，以 GEO/Bitmap/ZSet/Stream 等结构实现各类社交与地理功能。

## 技术栈

- **后端**：Spring Boot 2.3、Java 8、MyBatis-Plus
- **前端**：前后端分离，静态页面部署于 Nginx
- **存储**：MySQL 8、Redis（Lettuce + 连接池）
- **组件**：Redisson（分布式锁）、Hutool、Lombok、AOP
- **脚本**：Lua（原子扣库存、原子释放锁）
- **部署**：Nginx + Tomcat 集群、MySQL 集群、Redis 集群

## 技术栈应用

| 技术              | 用途                                                         |
| ----------------- | ------------------------------------------------------------ |
| Spring Boot / MVC | 后端框架与接口层，拦截器做登录校验                           |
| MyBatis-Plus      | 简化 CRUD、分页、条件构造                                    |
| Redis             | 缓存、分布式锁、Session 共享，以及 GEO / Bitmap / Set / ZSet / Stream 等数据结构 |
| Redisson          | 封装分布式锁，用于「一人一单」串行化                         |
| Lua               | 秒杀资格原子预检、锁的原子释放                               |
| Nginx             | 托管前端静态资源 + 反向代理后端接口，多实例负载均衡（模拟集群） |

## 亮点与难点

- **缓存三兄弟（穿透 / 雪崩 / 击穿）**：基于 Cache Aside 模式，空值缓存 + 随机 TTL 防穿透/雪崩，逻辑过期 + 互斥锁异步重建防击穿；并抽取为泛型 `CacheClient` 通用组件。
- **分布式锁**：`SimpleRedisLock` 用 SETNX + UUID 标识，Lua 脚本「比对后删除」避免误删；生产环境用 Redisson 实现可续期锁。
- **秒杀**：Lua 脚本在 Redis 内原子完成「查库存 + 查购买上限 + 扣库存 + XADD 发消息」，配合乐观锁 `stock>0` 防超卖、分布式锁防一人多单；订单写入经 Redis Stream 消费者组异步化，pending-list 兜底重试。
- **全局 ID 生成器**：`RedisIdWorker` 以时间戳 + Redis 自增序列按二进制拼接，替代自增主键，避免订单号规律泄露。
- **附近商户**：Redis GEO 按类型存储坐标，`GEOSEARCH` 半径搜索 + 距离排序。
- **签到**：Bitmap 记录每日签到，`BITFIELD` + 位运算统计连续签到天数。
- **社交 Feed**：Set 存关注列表、`SINTER` 求共同关注；推模式把博文推入粉丝 ZSet 信箱，score + offset 实现滚动分页。
