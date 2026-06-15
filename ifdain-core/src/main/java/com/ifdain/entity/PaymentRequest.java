package com.ifdain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 外部程序支付请求实体
 *
 * <p>记录外部程序通过 API 发起的支付请求，用于追踪支付状态并在支付完成后回调通知。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "payment_requests")
public class PaymentRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外暴露的请求标识 (UUID) */
    @Column(nullable = false, unique = true, length = 64)
    private String requestId;

    /** 爱发电用户 ID */
    @Column(nullable = false, length = 64)
    private String userId;

    /** 爱发电方案 ID */
    @Column(nullable = false, length = 64)
    private String planId;

    /** 外部程序自定义订单号 (用于 Webhook 匹配) */
    @Column(nullable = false, unique = true, length = 128)
    private String customOrderId;

    /** 支付成功后的回调地址 */
    @Column(nullable = false, length = 512)
    private String callbackUrl;

    /** 请求状态 */
    @Column(nullable = false)
    private PaymentRequestStatus status;

    /** 爱发电订单号 (支付后由 Webhook 填充) */
    @Column(length = 64)
    private String outTradeNo;

    /** 回调签名密钥 (Base64, 创建时随机生成) */
    @Column(nullable = false, length = 64)
    private String signatureSecret;

    /** 预期支付金额 */
    @Column(precision = 10, scale = 2)
    private BigDecimal totalAmount;

    /** 备注 */
    @Column(length = 256)
    private String remark;

    /** 回调重试次数 */
    @Column(nullable = false)
    @Builder.Default
    private Integer callbackRetryCount = 0;

    /** 最近一次回调尝试时间 */
    private LocalDateTime callbackLastAttempt;

    /** 最近回调失败原因 */
    @Column(length = 512)
    private String callbackError;

    /** 创建时间 */
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = PaymentRequestStatus.PENDING;
        if (callbackRetryCount == null) callbackRetryCount = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** 是否已支付 */
    @Transient
    public boolean isPaid() {
        return status == PaymentRequestStatus.PAID || status == PaymentRequestStatus.CALLBACK_SUCCESS;
    }

    /** 回调是否成功 */
    @Transient
    public boolean isCallbackSuccess() {
        return status == PaymentRequestStatus.CALLBACK_SUCCESS;
    }
}
