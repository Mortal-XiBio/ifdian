package com.ifdain.admin;

import com.ifdain.config.IfdainProperties;
import lombok.extern.slf4j.Slf4j;
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

/**
 * Redis 自动配置
 *
 * <p>根据运行时配置（SystemConfigService 或 application.yml）动态创建 Redis 连接。
 * 仅在 {@code ifdain.redis-enabled=true} 时生效。</p>
 */
@Slf4j
@Configuration
@ConditionalOnClass({RedisConnectionFactory.class, RedisTemplate.class})
@EnableConfigurationProperties(IfdainProperties.class)
public class RedisAutoConfiguration {

    private final SystemConfigService configService;
    private final IfdainProperties properties;

    public RedisAutoConfiguration(SystemConfigService configService, IfdainProperties properties) {
        this.configService = configService;
        this.properties = properties;
    }

    /**
     * Redis 连接工厂
     * <p>仅当启用 Redis 时创建。</p>
     */
    @Bean
    @ConditionalOnClass(RedisConnectionFactory.class)
    public RedisConnectionFactory redisConnectionFactory() {
        // 检查 Redis 是否启用
        String enabledStr = configService.getOrDefault(
                SystemConfigService.KEY_REDIS_ENABLED,
                String.valueOf(properties.isRedisEnabled()));
        boolean enabled = "true".equals(enabledStr);

        if (!enabled) {
            log.info("[Ifdain] Redis is disabled, skipping RedisConnectionFactory creation");
            // 返回一个未连接的工厂作为占位
            return new LettuceConnectionFactory();
        }

        // 从运行时配置读取（优先）或回退到静态默认值
        String host = configService.getOrDefault(
                SystemConfigService.KEY_REDIS_HOST, properties.getRedisHost());
        int port = Integer.parseInt(configService.getOrDefault(
                SystemConfigService.KEY_REDIS_PORT, String.valueOf(properties.getRedisPort())));
        String password = configService.getOrDefault(
                SystemConfigService.KEY_REDIS_PASSWORD, properties.getRedisPassword());
        int database = Integer.parseInt(configService.getOrDefault(
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
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        // 如果 Redis 未启用，connectionFactory 是未连接的占位，仍然可以创建 template
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
