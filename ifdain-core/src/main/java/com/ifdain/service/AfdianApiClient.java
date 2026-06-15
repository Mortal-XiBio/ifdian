package com.ifdain.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifdain.config.IfdainProperties;
import com.ifdain.util.AfdianSignatureUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 爱发电 API 客户端 (主动调用)
 *
 * <p>封装签名生成与 HTTP 调用，提供查询订单/赞助者等接口。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AfdianApiClient {

    private final IfdainProperties properties;
    private final ObjectMapper objectMapper;

    // ==================== 查询订单 ====================

    /**
     * 查询订单列表
     *
     * @param page     页码 (从 1 开始)
     * @param perPage  每页条数 (默认 50, 最大 100)
     * @return API 响应 JSON
     */
    public JsonNode queryOrders(int page, int perPage) {
        Map<String, Object> params = new HashMap<>();
        params.put("page", page);
        params.put("per_page", perPage);
        return callApi("/api/open/query-order", params);
    }

    /**
     * 按订单号查询
     *
     * @param outTradeNos 订单号列表 (最多一批)
     * @return API 响应 JSON
     */
    public JsonNode queryOrdersByNos(List<String> outTradeNos) {
        Map<String, Object> params = new HashMap<>();
        params.put("out_trade_no", String.join(",", outTradeNos));
        return callApi("/api/open/query-order", params);
    }

    // ==================== 查询赞助者 ====================

    /**
     * 查询赞助者列表
     *
     * @param page    页码
     * @param perPage 每页条数
     * @return API 响应 JSON
     */
    public JsonNode querySponsors(int page, int perPage) {
        Map<String, Object> params = new HashMap<>();
        params.put("page", page);
        params.put("per_page", perPage);
        return callApi("/api/open/query-sponsor", params);
    }

    // ==================== 查询方案 ====================

    /**
     * 查询方案详情
     *
     * @param planId 方案ID
     * @return API 响应 JSON（包含方案类型、SKU 列表等）
     */
    public JsonNode queryPlan(String planId) {
        Map<String, Object> params = new HashMap<>();
        params.put("plan_id", planId);
        return callApi("/api/open/query-plan", params);
    }

    /**
     * 查询方案下的 SKU 详情
     *
     * @param planId 方案ID
     * @param skuId  SKU ID
     * @return API 响应 JSON
     */
    public JsonNode queryPlanSku(String planId, String skuId) {
        Map<String, Object> params = new HashMap<>();
        params.put("plan_id", planId);
        params.put("sku_id", skuId);
        return callApi("/api/open/query-plan", params);
    }

    // ==================== 私信 ====================

    /**
     * 发送私信给赞助者
     *
     * <p>注意: 爱发电接口限频 10次/秒 或 1000次/小时</p>
     *
     * @param recipient 接收者爱发电用户ID
     * @param content   私信内容
     * @return API 响应 JSON
     */
    public JsonNode sendPrivateMessage(String recipient, String content) {
        Map<String, Object> params = new HashMap<>();
        params.put("recipient", recipient);
        params.put("content", content);
        return callApi("/api/open/send-msg", params);
    }

    // ==================== 自动回复 ====================

    /**
     * 更新方案自动回复内容
     *
     * @param planId                方案ID（与 skuId 二选一）
     * @param skuId                 SKU ID（与 planId 二选一）
     * @param autoReply             固定自动回复内容
     * @param autoRandomReply       随机自动回复内容（多条用换行分隔）
     * @param updateRandomReplyType 更新方式: "append" 追加 / "overwrite" 覆盖
     * @return API 响应 JSON
     */
    public JsonNode updatePlanReply(String planId, String skuId, String autoReply,
                                     String autoRandomReply, String updateRandomReplyType) {
        Map<String, Object> params = new HashMap<>();
        if (planId != null) params.put("plan_id", planId);
        if (skuId != null) params.put("sku_id", skuId);
        if (autoReply != null) params.put("auto_reply", autoReply);
        if (autoRandomReply != null) params.put("auto_random_reply", autoRandomReply);
        if (updateRandomReplyType != null) params.put("update_random_reply_type", updateRandomReplyType);
        return callApi("/api/open/update-plan-reply", params);
    }

    /**
     * 根据订单号查询随机自动回复
     *
     * @param outTradeNo 订单号
     * @return API 响应 JSON（包含该订单对应的随机回复内容）
     */
    public JsonNode queryRandomReply(String outTradeNo) {
        Map<String, Object> params = new HashMap<>();
        params.put("out_trade_no", outTradeNo);
        return callApi("/api/open/query-random-reply", params);
    }

    // ==================== 其他 API ====================

    /**
     * 测试签名连通性
     *
     * @return true 如果签名正确 (ec=200)
     */
    public boolean ping() {
        JsonNode result = callApi("/api/open/ping", new HashMap<>());
        return result != null && result.path("ec").asInt() == 200;
    }

    // ==================== 方案发现 ====================

    /**
     * 从订单数据中提取所有赞助方案
     *
     * <p>爱发电 API 没有「列出所有方案」的接口，因此通过遍历近期订单来收集
     * 出现过的方案 ID 和名称。</p>
     *
     * @param maxPages 最多扫描的订单页数 (建议 2~5 页)
     * @param perPage  每页条数 (最大 100)
     * @return 去重后的方案列表 [{plan_id, plan_title}]
     */
    public List<Map<String, String>> discoverPlans(int maxPages, int perPage) {
        Map<String, String> planMap = new LinkedHashMap<>();
        int actualPerPage = Math.min(perPage, 100);

        for (int page = 1; page <= maxPages; page++) {
            JsonNode result = queryOrders(page, actualPerPage);
            if (result == null || result.path("ec").asInt() != 200) {
                break;
            }

            JsonNode data = result.path("data");
            // 兼容 data 为 JSON 字符串的情况
            if (data.isTextual()) {
                try {
                    data = objectMapper.readTree(data.asText());
                } catch (Exception e) {
                    log.warn("[Ifdain] discoverPlans: data is text but not valid JSON");
                    break;
                }
            }

            JsonNode list = data.path("list");
            int totalPage = data.path("total_page").asInt(0);

            if (!list.isArray() || list.isEmpty()) {
                break;
            }

            for (JsonNode order : list) {
                String planId = order.path("plan_id").asText();
                String planTitle = order.path("plan_title").asText("");
                if (planId != null && !planId.isEmpty() && !planMap.containsKey(planId)) {
                    planMap.put(planId, planTitle.isEmpty() ? planId : planTitle);
                }
            }

            // 已到达最后一页
            if (page >= totalPage && totalPage > 0) {
                break;
            }

            // 避免请求过快
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        List<Map<String, String>> plans = new ArrayList<>();
        for (Map.Entry<String, String> entry : planMap.entrySet()) {
            Map<String, String> plan = new LinkedHashMap<>();
            plan.put("plan_id", entry.getKey());
            plan.put("plan_title", entry.getValue());
            plans.add(plan);
        }

        log.info("[Ifdain] discoverPlans: found {} unique plans from {} pages", plans.size(), maxPages);
        return plans;
    }

    // ==================== 核心调用 ====================

    /**
     * 调用爱发电 API
     *
     * @param path   API 路径 (如 /api/open/query-order)
     * @param params 请求参数字典
     * @return 响应 JSON
     */
    public JsonNode callApi(String path, Map<String, Object> params) {
        HttpURLConnection conn = null;
        try {
            String paramsJson = objectMapper.writeValueAsString(params);
            long ts = System.currentTimeMillis() / 1000;

            String sign = AfdianSignatureUtil.signApiRequest(
                    properties.getApiToken(),
                    paramsJson,
                    ts,
                    properties.getUserId()
            );

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("user_id", properties.getUserId());
            requestBody.put("params", paramsJson);
            requestBody.put("ts", ts);
            requestBody.put("sign", sign);

            String requestJson = objectMapper.writeValueAsString(requestBody);

            String urlStr = properties.getApiBaseUrl() + path;
            URL url = URI.create(urlStr).toURL();

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout((int) properties.getApiTimeoutMs());
            conn.setReadTimeout((int) properties.getApiTimeoutMs());

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestJson.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.error("[Ifdain] API call failed, path={}, httpCode={}", path, responseCode);
                return null;
            }

            byte[] responseBytes = conn.getInputStream().readAllBytes();
            String responseBody = new String(responseBytes, StandardCharsets.UTF_8);

            JsonNode result = objectMapper.readTree(responseBody);
            int ec = result.path("ec").asInt();

            if (ec != 200) {
                log.warn("[Ifdain] API returned non-200 ec={}, path={}, response={}",
                        ec, path, responseBody);
            }

            return result;

        } catch (Exception e) {
            log.error("[Ifdain] API call error, path={}", path, e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
