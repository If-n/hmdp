package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //解决穿透&雪崩
        //Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //解决击穿(setnx)
        //Shop shop=queryWithMutex(id);

        //解决击穿(逻辑过期)
        Shop shop = cacheClient.queryWithLogicalExpire(
                RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, 10L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("no such shop");
        }

        return Result.ok(shop);

    }

    /*//创建一个线程池
    private ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //解决击穿(逻辑过期)
    private Shop queryWithLogicalExpire(Long id) {
        //1.查redis
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.redis查询失败-不是热点数据-返回null
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        //3.redis查询成功，取出redisData
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //3.1 检查逻辑过期字段,转换ShopJson2shop
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject shopData = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(shopData, Shop.class);
        if (expireTime.isAfter(LocalDateTime.now())) {
            //3.1.1未过期返回shop数据
            return shop;
        }
        //3.1.2 已过期-重建缓存
        //3.2 获取该数据的锁
        String lockOfKey = RedisConstants.LOCK_SHOP_KEY + id;
        String isLock = tryLock(lockOfKey);
        //3.2.1 获取成功-获取一个线程并提交保存数据任务
        if (isLock != null) {
            //不需要double check，因为本来就允许返回旧数据
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShopToRedisWithExpire(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockOfKey, isLock);
                }
            });
        }

        //3.2.2 获取成功/失败-返回旧数据
        return shop;

    }

    //加上逻辑过期字段并保存数据
    public void saveShopToRedisWithExpire(Long id, Long expireTime) {
        //根据id查询shop数据
        Shop shop = getById(id);

        //加入expire字段，组成新对象
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));

        //保存到redis中
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

    }

    //解决缓存穿透&雪崩
    private Shop queryWithPassThrough(long id) {
        //1.redis查询
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.存在直接返回（存在有效数据）
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //2.5 存在但值为“”（空数据拦截）
        if (shopJson != null) {
            return null;
        }

        //3.真的不存在，查询db
        Shop shop = getById(id);
        //3.1 db不存在
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //3.2 db存在
        //3.3 存入redis
        long randomTTL = RandomUtil.randomLong(0L, 2L);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL + randomTTL, TimeUnit.MINUTES);
        //4.返回shop
        return shop;
        //end
    }

    //解决缓存击穿(setnx锁)
    private Shop queryWithMutex(long id) throws InterruptedException {
        //1.redis查询
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.存在直接返回（存在有效数据）
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //2.5 存在但值为“”（空数据拦截）
        if (shopJson != null) {
            return null;
        }

        //3.真的不存在，查询db，重建缓存

        //3.1获取锁
        String LockOfKey = RedisConstants.LOCK_SHOP_KEY + id;
        String isLock = tryLock(LockOfKey);
        //3.1.1 获取锁失败
        if (isLock == null) {
            //递归尝试获取锁
            Thread.sleep(100);
            return queryWithMutex(id);
        }
        //3.1.2 获取锁成功，重建缓存
        Shop shop = null;
        try {
            //3.1.2.1 再次检查缓存是否建立,已存在则返回
            String newShopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(newShopJson)) {
                return JSONUtil.toBean(newShopJson, Shop.class);
            }
            //确实不存在，重建缓存
            //3.1.3 查询SQL中shop数据
            shop = getById(id);
            //3.1.4 db不存在
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //3.3 db存在
            //3.3.1 存入redis
            long randomTTL = RandomUtil.randomLong(0L, 2L);
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL + randomTTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //3.3.2 释放锁
            unLock(LockOfKey, isLock);
        }
        //4.返回shop
        return shop;
    }

    //尝试获取锁
    private String tryLock(String LockKey) {
        String uuid = UUID.randomUUID().toString();
        Boolean setnx = stringRedisTemplate.opsForValue().setIfAbsent(LockKey, uuid, 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(setnx) ? uuid : null;
    }


    // 释放锁：Lua 脚本保证「是自己的锁才删」，避免删掉别人的锁
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "  return redis.call('del', KEYS[1]) " + "else " + "  return 0 " + "end", Long.class
    );

    //解锁
    private void unLock(String LockKey, String isLock) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(LockKey), isLock);
    }

*/
    //更新shop信息
    @Override
    @Transactional
    public Result update(Shop shop) {
        //更新后的数据主键不能为空
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(key);
        return Result.ok();

    }

    //根据店铺类型查询，并按距离排序
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.坐标可能获取不到，可以为空，坐标为空时直接分页查询并返回
        if (x == null || y == null) {
            Page<Shop> shops = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(shops.getRecords());
        }
        //2.有坐标的情况
        //3.先按type查询redis中的店铺id和坐标信息,并按距离排序
        //3.1查询key
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        //3.2分页查询数据起始参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //查询结果属性：entry<name,point>的集合列表、平均距离。集合列表属性例如：[<<name,point>,distance>,...]
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                //查询key对应的geoSet
                key,
                //查询范围圆的圆心
                GeoReference.fromCoordinate(x, y),
                //查询距离限制
                new Distance(5000),
                //只能查询end之前的所有数据,附带距离
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );

        //非空判断
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        //4.解析数据信息
        //4.1获取shop+distance集合
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> shopList = results.getContent();
        //提前非空判断
        if (from >= shopList.size()) {
            return Result.ok(Collections.emptyList());
        }
        //4.2解析分页后的数据范围的shopid并收集到list里面,收集距离信息放到map里
        Map<String, Distance> shopDisMap = new ConcurrentHashMap<>(shopList.size());
        List<String> shopIds = new ArrayList<>(shopList.size());
        shopList.stream()
                //跳过前面的(有可能全跳过，需要提前判断非空)
                .skip(from)
                //遍历shop+distance获得shopid和距离信息
                .forEach((GeoResult<RedisGeoCommands.GeoLocation<String>> shopGeoInfo) -> {
                    //获得shopid
                    String shopId = shopGeoInfo.getContent().getName();
                    //加入shopidList
                    shopIds.add(shopId);
                    //获得distance
                    Distance distance = shopGeoInfo.getDistance();
                    //放入map集合
                    shopDisMap.put(shopId, distance);
                });
        //5.按照shopid查询db中店铺的具体信息
        String idsStr = StrUtil.join(",", shopIds);//保证顺序
        List<Shop> shops = query().in("id", shopIds).last("ORDER BY FIELD(id," + idsStr + ")").list();
        //6.给每个shop设置当前距离
        shops.forEach(shop-> shop.setDistance(shopDisMap.get(shop.getId().toString()).getValue()));
        //6.返回shop集合
        return Result.ok(shops);
    }
}
