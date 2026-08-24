package com.hmdp.utils;

public interface ILock {
    //尝试获取redis分布式锁
    boolean tryLock(long timeoutSec);

    //释放锁
    void unlock();
}
