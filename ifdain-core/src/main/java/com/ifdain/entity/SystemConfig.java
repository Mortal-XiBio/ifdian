package com.ifdain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 系统配置项实体 - 对应 system_config 表
 *
 * <p>提供运行时配置存储，支持通过管理后台动态修改配置。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "system_config")
public class SystemConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** 配置键 (唯一) */
    @Column(nullable = false, unique = true, length = 128)
    private String configKey;

    /** 配置值 */
    @Column(columnDefinition = "text")
    private String configValue;

    /** 配置描述 */
    @Column(length = 256)
    private String description;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
