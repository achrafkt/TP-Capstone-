package com.example.service;

/**
 * 并发修改冲突：乐观锁检测到数据已被其他事务修改（版本号不一致）。
 * 调用方应重新加载数据后重试。
 */
public class ModificationConcurrenteException extends ReservationException {

    public ModificationConcurrenteException(String message, Throwable cause) {
        super(message, cause);
    }
}
