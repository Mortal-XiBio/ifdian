package com.ifdain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 爱发电订单实体 - 对应 ifdian_orders 表
 *
 * <p>字段覆盖 Webhook 推送的全部字段，同时包含扩展处理字段。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "ifdian_orders")
public class IfdianOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** 爱发电订单号 (唯一) */
    @Column(nullable = false, unique = true, length = 64)
    private String outTradeNo;

    /** 自定义订单ID (下单时传入) */
    @Column(length = 128)
    private String customOrderId;

    /** 爱发电用户ID (下单用户) */
    @Column(nullable = false, length = 64)
    private String userId;

    /** 用户唯一标识 (OAuth2 关联用) */
    @Column(length = 64)
    private String userPrivateId;

    /** 方案ID */
    @Column(nullable = false, length = 64)
    private String planId;

    /** 赞助月数 */
    @Column(name = "sponsor_month")
    private Integer sponsorMonth;

    /** 实付金额 (元) */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    /** 显示金额 (折扣前) */
    @Column(precision = 10, scale = 2)
    private BigDecimal showAmount;

    /** 订单状态: 2=交易成功 */
    @Column(nullable = false)
    private OrderStatus status;

    /** 用户留言 */
    @Column(columnDefinition = "text")
    private String remark;

    /** 兑换码ID */
    @Column(length = 128)
    private String redeemId;

    /** 方案类型: 0=常规方案 1=售卖方案 */
    @Column(nullable = false)
    private Integer productType;

    /** 折扣金额 */
    @Column(precision = 10, scale = 2)
    private BigDecimal discount;

    /** 售卖商品 SKU 明细 (JSON) */
    @Column(columnDefinition = "json")
    private String skuDetail;

    /** 收货人姓名 */
    @Column(length = 64)
    private String addressPerson;

    /** 收货人电话 */
    @Column(length = 32)
    private String addressPhone;

    /** 收货地址 */
    @Column(length = 256)
    private String addressAddress;

    /** Webhook 接收时间 (爱发电未在 Webhook 中提供精确支付时间) */
    private LocalDateTime receivedAt;

    /** 原始 Webhook 数据 (JSON 快照) */
    @Column(columnDefinition = "json")
    private String rawData;

    /** 业务处理标记: 0=未处理 1=已处理 */
    @Column(nullable = false)
    @Builder.Default
    private Integer processed = 0;

    /** Webhook 重试计数 */
    @Column(nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    /** 最近一次处理失败原因 */
    @Column(length = 512)
    private String errorMsg;

    /** 记录创建时间 */
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** 记录更新时间 */
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (processed == null) processed = 0;
        if (retryCount == null) retryCount = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ===== 便捷查询方法 =====

    /** 订单是否已支付成功 */
    @JsonIgnore
    @Transient
    public boolean isPaid() {
        return status == OrderStatus.PAID;
    }

    /** 订单是否已业务处理 */
    @JsonIgnore
    @Transient
    public boolean isProcessed() {
        return processed != null && processed == 1;
    }
}
