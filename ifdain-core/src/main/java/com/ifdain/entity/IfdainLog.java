package com.ifdain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 爱发电审计日志实体 — 记录关键业务事件
 *
 * <p>用于持久化重要的操作日志（Webhook 接收、订单处理、API 调用异常等），
 * 便于排查问题和审计追溯。日志会同时通过 SLF4J 输出到应用日志文件。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "ifdain_logs")
public class IfdainLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 日志级别: INFO / WARN / ERROR */
    @Column(nullable = false, length = 16)
    private String level;

    /** 事件来源/类别 (如 webhook, order, api, system) */
    @Column(nullable = false, length = 32)
    private String source;

    /** 日志消息摘要 */
    @Column(nullable = false, length = 512)
    private String message;

    /** 关联的爱发电订单号 (可为空) */
    @Column(length = 64)
    private String outTradeNo;

    /** 异常栈摘要 (仅 ERROR 级别) */
    @Column(columnDefinition = "text")
    private String exception;

    /** 记录创建时间 */
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
