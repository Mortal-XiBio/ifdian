package com.ifdain.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 爱发电集成配置项
 *
 * <p>前缀: {@code ifdain}</p>
 *
 * <h3>两种运行模式</h3>
 * <ul>
 *   <li><b>embedded</b> (默认): H2 内存库，自动建表，适合嵌入宿主项目使用</li>
 *   <li><b>standalone</b>: MySQL 数据库，schema 需手动导入，适合独立部署</li>
 * </ul>
 *
 * <p>运行模式通过 {@code spring.datasource} 和 Spring Profile 自动检测，无需手动配置。</p>
 */
@Data
@ConfigurationProperties(prefix = "ifdain")
public class IfdainProperties {

    // ===== 开发者身份 =====

    /** 爱发电 user_id (开发者后台获取) */
    private String userId;

    /** API Token (用于主动调用 API) */
    private String apiToken;

    // ===== Webhook =====

    /** Webhook RSA 公钥 (用于验证 Webhook 签名，可选) */
    private String webhookPublicKey;

    /** 是否启用 Webhook 签名验证 (默认 true) */
    private boolean enableWebhookSignature = true;

    /** Webhook 端点路径 (默认 /webhook/ifdian) */
    private String webhookPath = "/webhook/ifdian";

    // ===== API =====

    /** 爱发电 API 基础地址 (默认 https://ifdian.net) */
    private String apiBaseUrl = "https://ifdian.net";

    /** API 请求超时 (毫秒，默认 10000) */
    private long apiTimeoutMs = 10000;

    // ===== 订单处理 =====

    /** 订单处理 Bean 名称 (自动注入) */
    private String orderProcessorBean = "defaultOrderProcessor";

    // ===== 回调配置 =====

    /** 外部支付回调最大重试次数 (默认 3) */
    private int callbackMaxRetries = 3;

    /** 外部支付回调 HTTP 超时 (毫秒，默认 10000) */
    private int callbackTimeoutMs = 10000;

    // ===== Redis 配置 =====

    /** 是否启用 Redis (默认 false) */
    private boolean redisEnabled = false;

    /** Redis 主机地址 (默认 localhost) */
    private String redisHost = "localhost";

    /** Redis 端口 (默认 6379) */
    private int redisPort = 6379;

    /** Redis 密码 (默认空) */
    private String redisPassword = "";

    /** Redis 数据库索引 (默认 0) */
    private int redisDatabase = 0;
}
