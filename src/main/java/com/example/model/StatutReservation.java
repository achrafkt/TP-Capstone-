package com.example.model;

/**
 * 预订状态。
 */
public enum StatutReservation {
    /** 已确认（占用会议室） */
    CONFIRMEE,
    /** 已取消（不再占用会议室） */
    ANNULEE,
    /** 已结束（历史记录） */
    TERMINEE
}
