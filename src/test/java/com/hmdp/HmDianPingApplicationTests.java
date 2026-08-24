package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;


@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService executorService=Executors.newFixedThreadPool(500);

    //测试全局id生成器
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch=new CountDownLatch(300);

        long start = System.currentTimeMillis();
        Runnable task= ()->{
            try {
                for (int i = 0; i < 100; i++) {
                    System.out.println("id"+redisIdWorker.nextId("test"));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                countDownLatch.countDown();
            }
        };

        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }

        try {
            countDownLatch.await();
            long end = System.currentTimeMillis();
            System.out.println("耗时："+(end-start));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            executorService.shutdown();
        }

    }

    //预热商铺数据
    @Test
    void testSaveRedisData(){
        for (int i = 1; i <= 14; i++) {
            Shop shop = shopService.getById(i);
            cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY+i,shop,10L, TimeUnit.SECONDS);
        }
    }

    //加载商铺坐标信息
    @Test
    void loadShopGeo(){
        //1.查询所有商铺信息
        List<Shop> allShop = shopService.list();
        //2.取出有效信息
        //2.1按shopType分类保存shop到一个map
        Map<Long, List<Shop>> shopByType = allShop.stream().collect(Collectors.groupingBy(new Function<Shop, Long>() {
            @Override
            public Long apply(Shop shop) {
                return shop.getTypeId();
            }
        }));
        //3.保存到redis
        for (Map.Entry<Long, List<Shop>> entry : shopByType.entrySet()) {
            //以shoptype为保存数据的key
            String key = RedisConstants.SHOP_GEO_KEY+ entry.getKey();
            //遍历shop按type组成一个list
            List<Shop> shops = entry.getValue();
            //list中只存每一个shop的shopid+坐标(point对象)(GeoLocation天然符合)
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
            for (Shop shop : shops) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
                //stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
            }
            //每次遍历添加一种shop
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }
}
