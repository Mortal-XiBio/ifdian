package com.ifdain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * OAuth2 用户绑定实体
 *
 * <p>记录爱发电 OAuth2 授权后获得的用户标识，用于关联本地系统用户与爱发电赞助者身份。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "ifdian_oauth2_bindings")
public class OAuth2Binding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 爱发电用户 ID (公开) */
    @Column(nullable = false, length = 64)
    private String userId;

    /** 爱发电用户唯一标识 (私有，用于关联订单) */
    @Column(nullable = false, length = 64)
    private String userPrivateId;

    /** 爱发电用户昵称 */
    @Column(length = 128)
    private String name;

    /** 爱发电用户头像 URL */
    @Column(length = 512)
    private String avatar;

    /** 业务系统自定义用户标识（供其他项目绑定用） */
    @Column(length = 128)
    private String localUserId;

    /** 绑定的客户端 ID (OAuth2 client_id) */
    @Column(length = 64)
    private String clientId;

    /** 绑定时使用的 access_token (加密存储或不存储) */
    @Column(length = 256)
    private String accessToken;

    /** 首次绑定时间 */
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
