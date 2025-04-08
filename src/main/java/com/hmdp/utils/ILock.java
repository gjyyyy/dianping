package com.hmdp.utils;

public interface ILock {

    boolean tryLock(long timeSeconds);

    void unlock();
}
