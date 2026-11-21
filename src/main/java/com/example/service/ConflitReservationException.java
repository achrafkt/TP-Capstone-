package com.example.service;

/**
 * 时间冲突：该会议室在所请求的时间段内已被预订。
 */
public class ConflitReservationException extends ReservationException {

    public ConflitReservationException(String message) {
        super(message);
    }
}
