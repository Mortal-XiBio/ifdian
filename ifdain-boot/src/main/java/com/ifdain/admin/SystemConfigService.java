package com.ifdain.admin;

import com.ifdain.entity.SystemConfig;
import com.ifdain.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 系统配置管理服务
 *
 * <p>提供运行时配置的读写能力，配置值存储在 system_config 表中。
 * 用于管理爱发电视听配置、Webhook 配置等。</p>
 */
@Service
@RequiredArgsConstructor
public class SystemConfigService {

    private final SystemConfigRepository configRepository;

    /**
     * 获取配置值
     */
    public Optional<String> get(String key) {
        return configRepository.findByConfigKey(key)
                .map(SystemConfig::getConfigValue);
    }

    /**
     * 获取配置值，带默认值
     */
    public String getOrDefault(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    /**
     * 获取多个配置
     */
    public Map<String, String> getBatch(String... keys) {
        Map<String, String> result = new HashMap<>();
        for (String key : keys) {
            get(key).ifPresent(v -> result.put(key, v));
        }
        return result;
    }

    /**
     * 获取所有配置
     */
    public Map<String, String> getAll() {
        List<SystemConfig> all = configRepository.findAll();
        Map<String, String> map = new HashMap<>();
        for (SystemConfig cfg : all) {
            map.put(cfg.getConfigKey(), cfg.getConfigValue());
        }
        return map;
    }

    public List<SystemConfig> getAllEntries() {
        return configRepository.findAll();
    }

    /**
     * 设置配置值
     *
     * <p>由调用方方法（如 setBatch、markSetupCompleted）提供事务边界。</p>
     */
    public void set(String key, String value, String description) {
        SystemConfig config = configRepository.findByConfigKey(key)
                .orElse(SystemConfig.builder()
                        .configKey(key)
                        .build());
        config.setConfigValue(value);
        if (description != null) {
            config.setDescription(description);
        }
        configRepository.save(config);
    }

    /**
     * 设置配置值（无描述）
     */
    public void set(String key, String value) {
        set(key, value, null);
    }

    /**
     * 批量设置配置
     */
    @Transactional
    public void setBatch(Map<String, String> entries) {
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            set(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 删除配置
     */
    public void delete(String key) {
        configRepository.deleteByConfigKey(key);
    }

    /**
     * 检查配置是否存在
     */
    public boolean exists(String key) {
        return configRepository.findByConfigKey(key).isPresent();
    }

    // ===== 便捷方法 =====

    /** 安装是否已完成 */
    public boolean isSetupCompleted() {
        return "true".equals(getOrDefault("setup_completed", "false"));
    }

    /** 标记安装完成 */
    @Transactional
    public void markSetupCompleted() {
        set("setup_completed", "true", "安装向导是否已完成");
    }

    // ===== 配置键常量 =====

    public static final String KEY_USER_ID = "ifdain.user_id";
    public static final String KEY_API_TOKEN = "ifdain.api_token";
    public static final String KEY_WEBHOOK_PUBLIC_KEY = "ifdain.webhook_public_key";
    public static final String KEY_WEBHOOK_PATH = "ifdain.webhook_path";
    public static final String KEY_ADMIN_USERNAME = "ifdain.admin_username";
    public static final String KEY_ADMIN_PASSWORD = "ifdain.admin_password";
    public static final String KEY_API_BASE_URL = "ifdain.api_base_url";
    public static final String KEY_SETUP_COMPLETED = "setup_completed";

    // Redis 配置键
    public static final String KEY_REDIS_ENABLED  = "ifdain.redis_enabled";
    public static final String KEY_REDIS_HOST     = "ifdain.redis_host";
    public static final String KEY_REDIS_PORT     = "ifdain.redis_port";
    public static final String KEY_REDIS_PASSWORD = "ifdain.redis_password";
    public static final String KEY_REDIS_DATABASE = "ifdain.redis_database";

    // 外部 API 配置键
    public static final String KEY_EXTERNAL_API_KEY    = "ifdain.external_api_key";
    public static final String KEY_CALLBACK_MAX_RETRIES = "ifdain.callback_max_retries";
    public static final String KEY_CALLBACK_TIMEOUT_MS  = "ifdain.callback_timeout_ms";

    // 浏览器自动化配置键
    public static final String KEY_AFDIAN_COOKIE = "ifdain.afdian_cookie";
}
