package com.ifdain.admin;

import com.ifdain.config.IfdainProperties;
import com.ifdain.entity.SystemConfig;
import com.ifdain.repository.SystemConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.Optional;

/**
 * Redis 自动配置
 *
 * <p>根据运行时配置（system_config 表或 application.yml）动态创建 Redis 连接。
 * 仅在 {@code ifdain.redis-enabled=true} 时生效。</p>
 *
 * <p>直接注入 {@link SystemConfigRepository} 而非 {@link SystemConfigService}，
 * 以打破 RedisCacheService → RedisTemplate → RedisConnectionFactory → SystemConfigService
 * 的循环依赖链。</p>
 *
 * <p>当 Redis 禁用时，{@link #redisConnectionFactory()} 返回 null（不注册 Bean），
 * {@link #redisTemplate(RedisConnectionFactory)} 通过 {@code @ConditionalOnBean}
 * 自动跳过，{@link RedisCacheService} 中 RedisTemplate 为 null，所有方法静默降级。</p>
 */
@Slf4j
@Configuration
@ConditionalOnClass({RedisConnectionFactory.class, RedisTemplate.class})
@EnableConfigurationProperties(IfdainProperties.class)
public class RedisAutoConfiguration {

    private final SystemConfigRepository configRepository;
    private final IfdainProperties properties;

    public RedisAutoConfiguration(SystemConfigRepository configRepository, IfdainProperties properties) {
        this.configRepository = configRepository;
        this.properties = properties;
    }

    /**
     * Redis 连接工厂
     * <p>当 Redis 禁用时返回 null，不注册该 Bean。</p>
     */
    @Bean
    @ConditionalOnClass(RedisConnectionFactory.class)
    public RedisConnectionFactory redisConnectionFactory() {
        String enabledStr = getConfigOrDefault(
                SystemConfigService.KEY_REDIS_ENABLED,
                String.valueOf(properties.isRedisEnabled()));
        boolean enabled = "true".equals(enabledStr);

        if (!enabled) {
            log.info("[Ifdain] Redis is disabled, skipping RedisConnectionFactory creation");
            return null;
        }

        String host = getConfigOrDefault(
                SystemConfigService.KEY_REDIS_HOST, properties.getRedisHost());
        // 防御性清理：去掉空格和尾部斜杠，避免 Lettuce 拼出 host/:port
        if (host != null) {
            host = host.trim();
            while (host.endsWith("/")) {
                host = host.substring(0, host.length() - 1);
            }
        }
        int port = Integer.parseInt(getConfigOrDefault(
                SystemConfigService.KEY_REDIS_PORT, String.valueOf(properties.getRedisPort())));
        String password = getConfigOrDefault(
                SystemConfigService.KEY_REDIS_PASSWORD, properties.getRedisPassword());
        int database = Integer.parseInt(getConfigOrDefault(
                SystemConfigService.KEY_REDIS_DATABASE, String.valueOf(properties.getRedisDatabase())));

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(host);
        config.setPort(port);
        if (password != null && !password.isEmpty()) {
            config.setPassword(password);
        }
        config.setDatabase(database);

        log.info("[Ifdain] Redis configured: host={}, port={}, database={}, password={}",
                host, port, database, (password != null && !password.isEmpty() ? "***" : "(empty)"));

        return new LettuceConnectionFactory(config);
    }

    /**
     * RedisTemplate
     * <p>仅在 RedisConnectionFactory Bean 存在时创建。</p>
     */
    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 从 system_config 表读取配置，不存在时回退到默认值。
     * <p>直接操作 Repository 而非 Service，避免循环依赖。</p>
     */
    private String getConfigOrDefault(String key, String defaultValue) {
        try {
            Optional<SystemConfig> config = configRepository.findByConfigKey(key);
            return config.map(SystemConfig::getConfigValue).orElse(defaultValue);
        } catch (Exception e) {
            log.debug("[Ifdain] Error reading config key={}, using default: {}", key, e.getMessage());
            return defaultValue;
        }
    }
}
