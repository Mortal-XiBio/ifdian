package com.ifdain.entity;

/**
 * 外部支付请求状态枚举
 *
 * <p>追踪外部程序发起的支付请求生命周期。</p>
 */
public enum PaymentRequestStatus {

    /** 已创建，等待用户支付 */
    PENDING(0),
    /** Webhook 已确认支付成功 */
    PAID(1),
    /** 回调通知外部程序成功 */
    CALLBACK_SUCCESS(2),
    /** 超时未支付 */
    EXPIRED(3),
    /** Webhook 返回非支付状态 */
    FAILED(4);

    private final int value;

    PaymentRequestStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static PaymentRequestStatus fromValue(int value) {
        for (PaymentRequestStatus s : values()) {
            if (s.value == value) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown PaymentRequestStatus value: " + value);
    }
}
