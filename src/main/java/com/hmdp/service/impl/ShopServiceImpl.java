package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.*;
import jodd.typeconverter.impl.LocalDateTimeConverter;
import org.redisson.api.RBloomFilter;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private BloomFilterUtil bloomFilterUtil;

    @Resource
    private CacheClient cacheClient;

    private RBloomFilter<Long> bloomFilter = null;

    @PostConstruct // 项目启动的时候执行该方法，也可以理解为在spring容器初始化的时候执行该方法
    public void init() {
        // 启动项目时初始化bloomFilter
        bloomFilter = bloomFilterUtil.create("bloomFilter", 100, 0.05);
        LambdaQueryWrapper<Shop> wrapper = new LambdaQueryWrapper<>();
        List<Shop> shopList = shopMapper.selectList(wrapper);
        shopList.forEach(shop->{
            bloomFilter.add(shop.getId());
        });
    }

    //根据id查询商铺信息
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透（布隆过滤器）和缓存击穿（互斥锁）
        //Shop shop = cacheClient.queryWithBloomAndLock
        //        (RedisConstants.CACHE_SHOP_KEY,id,Shop.class,bloomFilter,
        //               id2->getById(id2),RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);

        //用逻辑过期来解决缓存击穿(热点key)
        //Shop shop =  queryWithLogicalLock(id);
        Shop shop = cacheClient.queryWithLogicalLock(
                RedisConstants.CACHE_SHOP_KEY,1L,Shop.class,
                id3->getById(id3),20L,TimeUnit.SECONDS);

        if(shop == null){
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalLock(Long id){
        //从redis中查询
        String shopStr = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //不存在，报错
        if(StrUtil.isBlank(shopStr)){
            return null;
        }

        //存在,判断是否逻辑过期
        //转换为RedisData对象
        RedisData redisData = JSONUtil.toBean(shopStr, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //没逻辑过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //返回redis数据
            return shop;
        }

        //逻辑过期
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        //获取互斥锁
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //开启新线程去重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }
        //返回过期数据
        return shop;
    }
    public Shop queryWithBloomAndLock(Long id){
        if(bloomFilter.contains(id)){
            //从redis中查询
            String shopStr = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            //存在，返回
            if(StrUtil.isNotBlank(shopStr)){
                Shop shop = JSONUtil.toBean(shopStr, Shop.class);
                return shop;
            }
            //不存在，获取互斥锁
            String key = "lock:shop:"+id;
            Shop shop = null;
            try {
                boolean isTrue = tryLock(key);
                if(!isTrue){
                    Thread.sleep(50);
                    return queryWithBloomAndLock(id);
                }
                //拿到锁再查一次缓存
                shopStr = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
                //存在，返回
                if(StrUtil.isNotBlank(shopStr)){
                    shop = JSONUtil.toBean(shopStr, Shop.class);
                    return shop;
                }

                //不存在,查数据库
                shop = shopMapper.selectById(id);
                //模拟重建缓存的延迟
                Thread.sleep(200);
                //数据库里不存在，报错
                if(shop == null){
                    return null;
                }

                stringRedisTemplate.opsForValue()
                        .set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop)
                                ,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                unlock(key);
            }
            //返回结果
            return shop;
        }else{
            return null;
        }
    }

    //重建缓存（含逻辑过期时间）
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //查询数据库中的shop
        Shop shop = shopMapper.selectById(id);
        //模拟重建缓存延迟
        Thread.sleep(200L);
        //封装RedisData
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+shop.getId(),JSONUtil.toJsonStr(redisData));
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1");
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    //更新店铺信息并保证缓存和数据库一致性
    //采用先更新数据库再删除缓存的方法，并且等查询数据时再更新缓存
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺不存在");
        }
        //先更新数据库
        shopMapper.updateById(shop);

        //再删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //是否按坐标查询
        if(x == null || y == null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        int from = (current - 1)* SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().limit(end)
        );
        if(results == null){
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();

        //查到最后一页时，没商铺信息，返回空值
        if(list.size() <= from){
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());

        list.stream().skip(from).forEach(result ->{
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            distanceMap.put(shopIdStr,result.getDistance());
        });
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
