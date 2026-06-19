package com.ifdain.automation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifdain.admin.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 爱发电内部 API 服务 — 直接调用 ifdian.net 的 SPA 内部接口
 *
 * <p>相比 Playwright 浏览器自动化，直接 HTTP 调用快数十倍（毫秒级 vs 秒级），
 * 且不依赖浏览器安装。认证方式为 {@code auth_token} Cookie。</p>
 *
 * <h3>支持的端点</h3>
 * <ul>
 *   <li>{@code POST /api/creator/edit-plan} — 创建/编辑方案</li>
 *   <li>{@code POST /api/creator/delete-plan} — 删除方案</li>
 *   <li>{@code POST /api/creator/hide-plan} — 隐藏方案</li>
 *   <li>{@code POST /api/creator/show-plan} — 显示方案</li>
 *   <li>{@code GET  /api/creator/all-plans} — 列出全部方案</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IfdianPlanApiService {

    private final SystemConfigService configService;
    private final ObjectMapper objectMapper;

    private static final String DEFAULT_BASE_URL = "https://ifdian.net";

    // ==================== 方案列表 ====================

    /**
     * 获取当前创作者的全部方案
     *
     * @return API 响应结果（包含 success, plans 列表等）
     */
    public ApiResult listPlans() {
        try {
            String cookie = extractAuthCookie();
            if (cookie == null) {
                return ApiResult.failed("未配置爱发电登录 Cookie，请先在系统设置中配置");
            }

            String baseUrl = configService.getOrDefault(
                    SystemConfigService.KEY_API_BASE_URL, DEFAULT_BASE_URL);
            JsonNode resp = doGet(baseUrl + "/api/creator/all-plans", cookie);

            if (resp == null) {
                return ApiResult.failed("API 请求失败，无法连接爱发电服务器");
            }

            int ec = resp.path("ec").asInt();
            if (ec != 200) {
                String em = resp.path("em").asText("未知错误");
                if (ec == 401 || ec == 403 || em.contains("登录")) {
                    return ApiResult.needLogin("Cookie 已失效（ec=" + ec + "），请重新登录爱发电后更新 Cookie");
                }
                return ApiResult.failed("API 返回错误: ec=" + ec + ", em=" + em);
            }

            JsonNode data = resp.path("data");
            if (data.isTextual()) {
                data = objectMapper.readTree(data.asText());
            }

            // 解析方案列表
            List<Map<String, Object>> plans = new ArrayList<>();
            JsonNode planList = data.path("list");
            if (!planList.isArray()) {
                planList = data; // 有时 data 本身就是数组
            }
            if (planList.isArray()) {
                for (JsonNode p : planList) {
                    Map<String, Object> plan = new LinkedHashMap<>();
                    plan.put("plan_id", p.path("plan_id").asText(""));
                    plan.put("name", p.path("name").asText(""));
                    plan.put("price", p.path("price").asText(""));
                    plan.put("status", p.path("status").asInt(0));
                    plan.put("desc", p.path("desc").asText(""));
                    plans.add(plan);
                }
            }

            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("plans", plans);
            extra.put("planCount", plans.size());
            log.info("[PlanAPI] Listed {} plans", plans.size());
            return ApiResult.success("获取到 " + plans.size() + " 个方案", extra);

        } catch (Exception e) {
            log.error("[PlanAPI] listPlans failed", e);
            return ApiResult.failed("API 调用异常: " + e.getMessage());
        }
    }

    // ==================== 创建方案 ====================

    /**
     * 通过内部 API 创建新方案
     *
     * @param title       方案名称
     * @param price       月付价格（元）
     * @param description 方案描述
     * @return 创建结果（包含 plan_id）
     */
    public ApiResult createPlan(String title, Double price, String description) {
        try {
            String cookie = extractAuthCookie();
            if (cookie == null) {
                return ApiResult.failed("未配置爱发电登录 Cookie");
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("plan_id", "");  // 空 = 创建新方案
            body.put("name", title != null ? title : "");
            body.put("price", price != null ? String.valueOf(price) : "5");
            body.put("desc", description != null ? description : "");
            body.put("status", "");
            body.put("pic", "");
            body.put("reply_switch", "");
            body.put("reply_content", "");
            body.put("reply_random_switch", "");
            body.put("reply_random_content", "");
            body.put("independent", "");
            body.put("permanent", "");
            body.put("sku", Collections.emptyList());
            body.put("need_address", "");
            body.put("pay_month", "");
            body.put("favorable_price", "");
            body.put("product_type", "");

            String baseUrl = configService.getOrDefault(
                    SystemConfigService.KEY_API_BASE_URL, DEFAULT_BASE_URL);
            JsonNode resp = doPost(baseUrl + "/api/creator/edit-plan", body, cookie);

            if (resp == null) {
                return ApiResult.failed("API 请求失败");
            }

            int ec = resp.path("ec").asInt();
            String em = resp.path("em").asText("");
            if (ec != 200) {
                if (ec == 401 || ec == 403 || em.contains("登录")) {
                    return ApiResult.needLogin("Cookie 已失效，请重新登录爱发电后更新 Cookie");
                }
                return ApiResult.failed("创建失败: " + em + " (ec=" + ec + ")");
            }

            // 从响应中提取 plan_id
            JsonNode data = resp.path("data");
            if (data.isTextual()) {
                data = objectMapper.readTree(data.asText());
            }
            String planId = data.path("plan_id").asText("");
            if (planId.isEmpty()) {
                planId = data.path("id").asText("");
            }

            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("planId", planId);
            log.info("[PlanAPI] Plan created: {} (plan_id={})", title, planId);
            return ApiResult.success("方案「" + title + "」创建成功", extra);

        } catch (Exception e) {
            log.error("[PlanAPI] createPlan failed", e);
            return ApiResult.failed("API 调用异常: " + e.getMessage());
        }
    }

    // ==================== 删除方案 ====================

    /**
     * 通过内部 API 删除方案
     *
     * @param planId 方案 ID
     * @return 删除结果
     */
    public ApiResult deletePlan(String planId) {
        try {
            String cookie = extractAuthCookie();
            if (cookie == null) {
                return ApiResult.failed("未配置爱发电登录 Cookie");
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("plan_id", planId);

            String baseUrl = configService.getOrDefault(
                    SystemConfigService.KEY_API_BASE_URL, DEFAULT_BASE_URL);
            JsonNode resp = doPost(baseUrl + "/api/creator/delete-plan", body, cookie);

            if (resp == null) {
                return ApiResult.failed("API 请求失败");
            }

            int ec = resp.path("ec").asInt();
            String em = resp.path("em").asText("");
            if (ec != 200) {
                if (ec == 401 || ec == 403 || em.contains("登录")) {
                    return ApiResult.needLogin("Cookie 已失效");
                }
                return ApiResult.failed("删除失败: " + em + " (ec=" + ec + ")");
            }

            log.info("[PlanAPI] Plan deleted: {}", planId);
            return ApiResult.success("方案已删除", null);

        } catch (Exception e) {
            log.error("[PlanAPI] deletePlan failed", e);
            return ApiResult.failed("API 调用异常: " + e.getMessage());
        }
    }

    // ==================== 隐藏/显示方案 ====================

    /**
     * 隐藏方案
     *
     * @param planId 方案 ID
     * @return 操作结果
     */
    public ApiResult hidePlan(String planId) {
        return togglePlan(planId, "/api/creator/hide-plan", "隐藏");
    }

    /**
     * 显示方案
     *
     * @param planId 方案 ID
     * @return 操作结果
     */
    public ApiResult showPlan(String planId) {
        return togglePlan(planId, "/api/creator/show-plan", "显示");
    }

    /**
     * 切换方案显示/隐藏状态：先查询当前状态，再执行对应操作
     *
     * @param planId 方案 ID
     * @return 操作结果
     */
    public ApiResult togglePlanVisibility(String planId) {
        // 先查列表获取当前状态
        ApiResult listResult = listPlans();
        if (!listResult.isSuccess()) {
            return listResult;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plans = listResult.getExtra() != null
                ? (List<Map<String, Object>>) listResult.getExtra().get("plans")
                : null;

        if (plans == null) {
            return ApiResult.failed("无法获取方案列表");
        }

        // 找到目标方案的当前状态
        int currentStatus = -1;
        String planName = planId;
        for (Map<String, Object> p : plans) {
            if (planId.equals(p.get("plan_id"))) {
                currentStatus = (int) p.get("status");
                planName = (String) p.getOrDefault("name", planId);
                break;
            }
        }

        if (currentStatus == -1) {
            return ApiResult.failed("未找到方案 " + planId);
        }

        // status=0 显示中 → 隐藏；status=1 已隐藏 → 显示
        if (currentStatus == 0) {
            ApiResult r = hidePlan(planId);
            if (r.isSuccess()) {
                r.setMessage("方案「" + planName + "」已隐藏");
            }
            return r;
        } else {
            ApiResult r = showPlan(planId);
            if (r.isSuccess()) {
                r.setMessage("方案「" + planName + "」已显示");
            }
            return r;
        }
    }

    // ==================== 通过名称查找 plan_id ====================

    /**
     * 根据方案名称查找 plan_id
     *
     * @param title 方案名称
     * @return plan_id，未找到返回 null
     */
    public String findPlanIdByTitle(String title) {
        ApiResult result = listPlans();
        if (!result.isSuccess() || result.getExtra() == null) {
            return null;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plans = (List<Map<String, Object>>) result.getExtra().get("plans");
        if (plans == null) return null;

        for (Map<String, Object> p : plans) {
            if (title.equals(p.get("name"))) {
                return (String) p.get("plan_id");
            }
        }
        return null;
    }

    // ==================== 内部方法 ====================

    private ApiResult togglePlan(String planId, String path, String action) {
        try {
            String cookie = extractAuthCookie();
            if (cookie == null) {
                return ApiResult.failed("未配置爱发电登录 Cookie");
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("plan_id", planId);

            String baseUrl = configService.getOrDefault(
                    SystemConfigService.KEY_API_BASE_URL, DEFAULT_BASE_URL);
            JsonNode resp = doPost(baseUrl + path, body, cookie);

            if (resp == null) {
                return ApiResult.failed("API 请求失败");
            }

            int ec = resp.path("ec").asInt();
            String em = resp.path("em").asText("");
            if (ec != 200) {
                if (ec == 401 || ec == 403 || em.contains("登录")) {
                    return ApiResult.needLogin("Cookie 已失效");
                }
                return ApiResult.failed(action + "失败: " + em + " (ec=" + ec + ")");
            }

            log.info("[PlanAPI] Plan {} (plan_id={})", action, planId);
            return ApiResult.success("方案已" + action, null);

        } catch (Exception e) {
            log.error("[PlanAPI] togglePlan failed", e);
            return ApiResult.failed("API 调用异常: " + e.getMessage());
        }
    }

    /**
     * 从存储的 Cookie JSON 中提取 auth_token 值，构造 HTTP Cookie 头
     */
    private String extractAuthCookie() {
        String cookieJson = configService.getOrDefault(BrowserAutomationService.KEY_AFDIAN_COOKIE, "");
        if (cookieJson.isBlank()) {
            return null;
        }

        String trimmed = cookieJson.trim();

        // JSON 数组格式
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            try {
                List<Map<String, Object>> cookieList;
                if (trimmed.startsWith("[")) {
                    cookieList = objectMapper.readValue(trimmed,
                            new TypeReference<>() {});
                } else {
                    Map<String, Object> single = objectMapper.readValue(trimmed,
                            new TypeReference<>() {});
                    cookieList = Collections.singletonList(single);
                }

                // 构造完整的 Cookie 字符串 (name1=val1; name2=val2)
                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> c : cookieList) {
                    String name = String.valueOf(c.get("name"));
                    String value = String.valueOf(c.get("value"));
                    if ("null".equals(name) || name.isBlank()) continue;
                    if (!sb.isEmpty()) sb.append("; ");
                    sb.append(name).append("=").append(value);
                }
                return !sb.isEmpty() ? sb.toString() : null;

            } catch (Exception e) {
                log.warn("[PlanAPI] Failed to parse cookie JSON: {}", e.getMessage());
                return null;
            }
        }

        // document.cookie 格式 (name1=value1; name2=value2)
        if (trimmed.contains("=")) {
            return trimmed;
        }

        return null;
    }

    /**
     * 发送 POST 请求（JSON body）
     */
    private JsonNode doPost(String urlStr, Map<String, Object> body, String cookie) throws Exception {
        String bodyJson = objectMapper.writeValueAsString(body);
        URL url = URI.create(urlStr).toURL();

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://ifdian.net/setting/plan");
            conn.setRequestProperty("Origin", "https://ifdian.net");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyJson.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                log.warn("[PlanAPI] POST {} -> HTTP {}", urlStr, code);
                return null;
            }

            String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            log.debug("[PlanAPI] POST {} -> {}", urlStr, responseBody.length() > 500
                    ? responseBody.substring(0, 500) + "..." : responseBody);

            return objectMapper.readTree(responseBody);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 发送 GET 请求
     */
    private JsonNode doGet(String urlStr, String cookie) throws Exception {
        URL url = URI.create(urlStr).toURL();

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://ifdian.net/setting/plan");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            int code = conn.getResponseCode();
            if (code != 200) {
                log.warn("[PlanAPI] GET {} -> HTTP {}", urlStr, code);
                return null;
            }

            String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            log.debug("[PlanAPI] GET {} -> {}", urlStr, responseBody.length() > 500
                    ? responseBody.substring(0, 500) + "..." : responseBody);

            return objectMapper.readTree(responseBody);
        } finally {
            conn.disconnect();
        }
    }

    // ==================== 结果数据类 ====================

    @lombok.Data
    @lombok.Builder
    public static class ApiResult {
        private boolean success;
        private String message;
        private boolean needLogin;
        /** 额外数据 (plans 列表, planId 等) */
        private Map<String, Object> extra;

        public static ApiResult success(String message, Map<String, Object> extra) {
            return ApiResult.builder()
                    .success(true).message(message).extra(extra).build();
        }

        public static ApiResult failed(String message) {
            return ApiResult.builder()
                    .success(false).message(message).build();
        }

        public static ApiResult needLogin(String message) {
            return ApiResult.builder()
                    .success(false).needLogin(true).message(message).build();
        }
    }
}
