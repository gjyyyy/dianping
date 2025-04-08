package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public void set(String key,Object value,Long time,TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }


    public <R,ID> R queryWithLogicalLock(
            String keyPrefix, ID id, Class<R> type,Function<ID,R> dbCallback,Long time,TimeUnit unit){
        //从redis中查询
        String cacheKey = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        //不存在，报错
        if(StrUtil.isBlank(json)){
            return null;
        }

        //存在,判断是否逻辑过期
        //转换为RedisData对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //没逻辑过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //返回redis数据
            return r;
        }

        //逻辑过期
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        //获取互斥锁
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //开启新线程去重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r1 = dbCallback.apply(id);
                    //模拟重建缓存延迟
                    Thread.sleep(200L);
                    this.setWithLogicalExpire(cacheKey,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }
        //返回过期数据
        return r;
    }

    public <R, ID> R queryWithBloomAndLock
            (String keyPrefix, ID id, Class<R> type, RBloomFilter<ID> bloomFilter,
             Function<ID,R> dbCallback,Long time,TimeUnit unit){
        if(bloomFilter.contains(id)){
            //从redis中查询
            String cacheKey = keyPrefix + id;
            String json = stringRedisTemplate.opsForValue().get(cacheKey);
            //存在，返回
            if(StrUtil.isNotBlank(json)){
                R r = JSONUtil.toBean(json, type);
                return r;
            }
            //不存在，获取互斥锁
            String lockKey = "lock:shop:"+id;
            R r = null;
            try {
                boolean isTrue = tryLock(lockKey);
                if(!isTrue){
                    Thread.sleep(50);
                    return queryWithBloomAndLock(keyPrefix,id,type,bloomFilter,dbCallback,time,unit);
                }
                //拿到锁再查一次缓存
                json = stringRedisTemplate.opsForValue().get(cacheKey);
                //存在，返回
                if(StrUtil.isNotBlank(json)){
                    r = JSONUtil.toBean(json, type);
                    return r;
                }
                //不存在,查数据库
                r = dbCallback.apply(id);
                //模拟重建缓存的延迟
                Thread.sleep(200);
                //数据库里不存在，报错
                if(r == null){
                    return null;
                }

                //存在，先存入bloomFilter再存入redis
                bloomFilter.add(id);
                this.set(cacheKey,r,time,unit);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                unlock(lockKey);
            }
            //返回结果
            return r;
        }else{
            return null;
        }
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1");
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
