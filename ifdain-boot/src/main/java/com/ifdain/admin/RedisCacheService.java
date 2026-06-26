package com.ifdain.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存服务
 *
 * <p>对 {@link RedisTemplate} 的轻量封装，所有操作均 try-catch 包裹，
 * Redis 不可用时静默降级，不影响业务。</p>
 *
 * <p>键前缀: {@code ifdain:}</p>
 */
@Slf4j
@Service
@ConditionalOnClass(RedisTemplate.class)
public class RedisCacheService {

    private static final String KEY_PREFIX = "ifdain:";

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 读取缓存
     *
     * @param key 缓存键（不含前缀，自动追加 ifdain:）
     * @return 缓存值，未命中或异常返回 null
     */
    public String get(String key) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            Object val = redisTemplate.opsForValue().get(KEY_PREFIX + key);
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            log.debug("[Ifdain] Redis GET error, key={}: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 写入缓存（带过期时间）
     *
     * @param key     缓存键
     * @param value   缓存值
     * @param ttl     过期时间
     * @param unit    时间单位
     */
    public void put(String key, String value, long ttl, TimeUnit unit) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + key, value, ttl, unit);
        } catch (Exception e) {
            log.debug("[Ifdain] Redis SET error, key={}: {}", key, e.getMessage());
        }
    }

    /**
     * 写入缓存（秒级 TTL 快捷方法）
     */
    public void put(String key, String value, long ttlSeconds) {
        put(key, value, ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 删除缓存
     */
    public void evict(String key) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + key);
        } catch (Exception e) {
            log.debug("[Ifdain] Redis DEL error, key={}: {}", key, e.getMessage());
        }
    }

    /**
     * 按前缀批量删除缓存
     *
     * @param pattern 匹配模式（不含 ifdain: 前缀，自动追加），如 "config:*"
     */
    public void evictByPattern(String pattern) {
        if (redisTemplate == null) {
            return;
        }
        try {
            var keys = redisTemplate.keys(KEY_PREFIX + pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("[Ifdain] Redis evicted {} keys matching {}", keys.size(), pattern);
            }
        } catch (Exception e) {
            log.debug("[Ifdain] Redis KEYS error, pattern={}: {}", pattern, e.getMessage());
        }
    }

    /**
     * 检查键是否存在
     */
    public boolean exists(String key) {
        if (redisTemplate == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + key));
        } catch (Exception e) {
            log.debug("[Ifdain] Redis EXISTS error, key={}: {}", key, e.getMessage());
            return false;
        }
    }

    /**
     * 设置过期时间
     */
    public void expire(String key, long ttl, TimeUnit unit) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.expire(KEY_PREFIX + key, ttl, unit);
        } catch (Exception e) {
            log.debug("[Ifdain] Redis EXPIRE error, key={}: {}", key, e.getMessage());
        }
    }
}
