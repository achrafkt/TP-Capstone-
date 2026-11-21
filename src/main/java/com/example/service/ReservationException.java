package com.example.service;

/**
 * 业务异常基类：携带给最终用户看的、清晰的错误信息。
 */
public class ReservationException extends RuntimeException {

    public ReservationException(String message) {
        super(message);
    }

    public ReservationException(String message, Throwable cause) {
        super(message, cause);
    }
}
