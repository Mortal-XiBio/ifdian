package com.ifdain.entity;

/**
 * 订单状态枚举
 *
 * <p>数据库存储为整型值，通过 {@link OrderStatusConverter} 自动转换。</p>
 */
public enum OrderStatus {

    /** 未支付 */
    UNPAID(0),
    /** 交易成功 */
    PAID(2),
    /** 已取消 */
    CANCELLED(-1);

    private final int value;

    OrderStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /**
     * 根据数据库整型值解析枚举
     *
     * @param value 数据库值
     * @return 匹配的枚举
     * @throws IllegalArgumentException 未找到匹配的枚举
     */
    public static OrderStatus fromValue(int value) {
        for (OrderStatus status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown OrderStatus value: " + value);
    }
}
