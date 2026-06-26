package com.ifdain.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifdain.service.AfdianApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 带 Redis 缓存的爱发电 API 服务
 *
 * <p>对 {@link AfdianApiClient} 的读取类查询提供透明缓存，
 * Redis 未启用时直接透传到底层客户端。</p>
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>{@code queryPlan} — 方案详情，TTL 5 分钟</li>
 *   <li>{@code discoverPlans} — 方案发现，TTL 10 分钟</li>
 * </ul>
 *
 * <p>写操作（发私信、更新自动回复等）不做缓存。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CachedApiService {

    private static final long PLAN_TTL = 300;          // 方案详情 5 分钟
    private static final long DISCOVER_TTL = 600;      // 方案发现 10 分钟

    private final AfdianApiClient apiClient;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    @Lazy
    private RedisCacheService cache;

    /**
     * 查询方案详情（带缓存）
     */
    public JsonNode queryPlan(String planId) {
        String cacheKey = "api:plan:" + planId;
        JsonNode cached = getJsonNode(cacheKey);
        if (cached != null) {
            log.debug("[Ifdain] Cache hit: queryPlan({})", planId);
            return cached;
        }
        JsonNode result = apiClient.queryPlan(planId);
        if (result != null && result.path("ec").asInt() == 200) {
            putJsonNode(cacheKey, result, PLAN_TTL);
        }
        return result;
    }

    /**
     * 发现全部方案（带缓存）
     */
    public List<Map<String, String>> discoverPlans(int maxPages, int perPage) {
        String cacheKey = "api:discover:" + maxPages + ":" + perPage;
        String cached = cacheGet(cacheKey);
        if (cached != null) {
            try {
                List<Map<String, String>> plans = objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
                log.debug("[Ifdain] Cache hit: discoverPlans({}, {})", maxPages, perPage);
                return plans;
            } catch (JsonProcessingException e) {
                log.warn("[Ifdain] Cache deserialize error for discoverPlans, falling through", e);
            }
        }
        List<Map<String, String>> result = apiClient.discoverPlans(maxPages, perPage);
        if (result != null && !result.isEmpty()) {
            try {
                String json = objectMapper.writeValueAsString(result);
                cachePut(cacheKey, json, DISCOVER_TTL);
            } catch (JsonProcessingException e) {
                log.warn("[Ifdain] Cache serialize error for discoverPlans", e);
            }
        }
        return result;
    }

    /**
     * 清除方案相关缓存（方案更新后调用）
     */
    public void evictPlanCache(String planId) {
        if (cache != null) {
            cache.evict("api:plan:" + planId);
            cache.evictByPattern("api:discover:*");
        }
    }

    // ===== 缓存读写辅助方法 =====

    private JsonNode getJsonNode(String key) {
        String json = cacheGet(key);
        if (json == null) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.debug("[Ifdain] Cache deserialize error, key={}", key);
            return null;
        }
    }

    private void putJsonNode(String key, JsonNode node, long ttl) {
        try {
            cachePut(key, objectMapper.writeValueAsString(node), ttl);
        } catch (JsonProcessingException e) {
            log.debug("[Ifdain] Cache serialize error, key={}", key);
        }
    }

    private String cacheGet(String key) {
        if (cache == null) return null;
        return cache.get(key);
    }

    private void cachePut(String key, String value, long ttl) {
        if (cache != null) {
            cache.put(key, value, ttl);
        }
    }
}
