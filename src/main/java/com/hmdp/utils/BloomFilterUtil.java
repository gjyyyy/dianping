package com.hmdp.utils;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class BloomFilterUtil {

    @Resource
    private RedissonClient redissonClient;

    /**
     * 创建布隆过滤器
     *
     * @param filterName         过滤器名称
     * @param expectedInsertions 预测插入数量
     * @param falsePositiveRate  误判率
     */
    public <T> RBloomFilter<T> create(String filterName, long expectedInsertions, double falsePositiveRate) {
        RBloomFilter<T> bloomFilter = redissonClient.getBloomFilter(filterName);
        bloomFilter.tryInit(expectedInsertions, falsePositiveRate);
        return bloomFilter;
    }
}

