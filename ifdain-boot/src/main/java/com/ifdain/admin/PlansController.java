package com.ifdain.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifdain.automation.BrowserAutomationService;
import com.ifdain.automation.IfdianPlanApiService;
import com.ifdain.entity.OrderStatus;
import com.ifdain.repository.IfdianOrderRepository;
import com.ifdain.service.AfdianApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方案管理控制器
 *
 * <p>提供赞助方案的查看、发现、详情查询和创建功能。
 * 方案创建采用双通道策略：优先 iframe 嵌入爱发电页面，被阻止时
 * 自动降级为 Playwright 浏览器自动化。</p>
 */
@Slf4j
@Controller
@RequestMapping("${ifdain.admin.base-path}/plans")
@RequiredArgsConstructor
public class PlansController {

    private final IfdianOrderRepository orderRepository;
    private final AdminProperties adminProperties;
    private final SystemConfigService configService;
    private final AfdianApiClient apiClient;
    private final CachedApiService cachedApi;
    private final ObjectMapper objectMapper;
    private final BrowserAutomationService browserAutomation;
    private final IfdianPlanApiService planApiService;

    /**
     * 方案管理主页 — 展示本地数据库中已有的方案汇总
     */
    @GetMapping
    public String plans(Model model) {
        if (!configService.isSetupCompleted()) {
            return "redirect:" + adminProperties.getBasePath() + "/setup";
        }
        model.addAttribute("basePath", adminProperties.getBasePath());
        model.addAttribute("apiConfigured", isApiConfigured());

        // 浏览器自动化可用性
        boolean browserAvailable = browserAutomation.isAvailable();
        model.addAttribute("browserAutomationAvailable", browserAvailable);
        // Cookie 是否已配置
        boolean cookieConfigured = !configService.getOrDefault(
                BrowserAutomationService.KEY_AFDIAN_COOKIE, "").isBlank();
        model.addAttribute("afdianCookieConfigured", cookieConfigured);
        // API 模式可用性（只需要 Cookie 即可）
        model.addAttribute("apiModeAvailable", cookieConfigured);

        // 从本地数据库汇总所有时间范围内的已支付订单，按方案分组
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        var planSummary = orderRepository.summarizeByPlan(start, now, OrderStatus.PAID);
        model.addAttribute("planSummary", planSummary);

        return "admin/plans";
    }

    /**
     * 探测自动化状态 — AJAX 接口
     *
     * <p>前端据此判断：iframe 嵌入是否被允许、浏览器自动化是否可用、
     * 爱发电 Cookie 是否已配置。</p>
     */
    @GetMapping("/automation-status")
    @ResponseBody
    public Map<String, Object> automationStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        // 浏览器自动化是否已安装并可启动
        result.put("browserAvailable", browserAutomation.isAvailable());
        // 爱发电 Cookie 是否已配置
        boolean cookieConfigured = !configService.getOrDefault(
                BrowserAutomationService.KEY_AFDIAN_COOKIE, "").isBlank();
        result.put("cookieConfigured", cookieConfigured);
        // API 模式是否可用（只需 Cookie）
        result.put("apiModeAvailable", cookieConfigured);
        // 推荐方案: "api" > "browser" > "iframe"
        if (cookieConfigured) {
            result.put("recommendedMode", "api");
        } else if (browserAutomation.isAvailable()) {
            result.put("recommendedMode", "browser");
        } else {
            result.put("recommendedMode", "iframe");
        }
        return result;
    }

    /**
     * 列出全部方案 — AJAX 接口，通过内部 API 获取
     */
    @PostMapping("/list")
    @ResponseBody
    public Map<String, Object> listPlans() {
        IfdianPlanApiService.ApiResult apiResult = planApiService.listPlans();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", apiResult.isSuccess());
        result.put("message", apiResult.getMessage());
        result.put("needLogin", apiResult.isNeedLogin());
        if (apiResult.getExtra() != null) {
            result.put("plans", apiResult.getExtra().get("plans"));
            result.put("planCount", apiResult.getExtra().get("planCount"));
        }
        return result;
    }

    /**
     * 创建赞助方案 — AJAX 接口
     *
     * <p>优先使用伪 API 模式（毫秒级），失败时降级为浏览器自动化。</p>
     */
    @PostMapping("/create")
    @ResponseBody
    public Map<String, Object> createPlan(
            @RequestParam String title,
            @RequestParam(required = false) Double price,
            @RequestParam(required = false) String description,
            @RequestParam(defaultValue = "api") String mode) {

        if (price != null && (price < 5 || price > 20000)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "价格必须在 5 ~ 20000 元之间");
            return err;
        }

        // 优先 API 模式
        if ("api".equals(mode)) {
            IfdianPlanApiService.ApiResult apiResult = planApiService.createPlan(title, price, description);
            if (apiResult.isSuccess()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("planId", apiResult.getExtra() != null ? apiResult.getExtra().get("planId") : null);
                result.put("message", apiResult.getMessage());
                result.put("mode", "api");
                return result;
            }
            // API 失败且非 Cookie 问题 → 记录日志并降级
            if (!apiResult.isNeedLogin()) {
                log.warn("[Plans] API mode failed ({}), falling back to browser", apiResult.getMessage());
            } else {
                // Cookie 失效，不降级，直接返回
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", false);
                result.put("message", apiResult.getMessage());
                result.put("needLogin", true);
                result.put("mode", "api");
                return result;
            }
        }

        // 降级：浏览器自动化
        BrowserAutomationService.PlanCreationRequest request =
                BrowserAutomationService.PlanCreationRequest.builder()
                        .title(title)
                        .price(price)
                        .description(description)
                        .build();

        BrowserAutomationService.PlanCreationResult creationResult =
                browserAutomation.createPlan(request);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", creationResult.isSuccess());
        result.put("planId", creationResult.getPlanId());
        result.put("message", creationResult.getMessage());
        result.put("needLogin", creationResult.isNeedLogin());
        result.put("mode", "browser");
        if (creationResult.getScreenshotBase64() != null) {
            result.put("screenshotBase64", creationResult.getScreenshotBase64());
        }
        return result;
    }

    /**
     * 删除赞助方案 — AJAX 接口
     *
     * <p>优先使用伪 API 模式（通过方案名称查找 plan_id，再调用删除 API），
     * 失败时降级为浏览器自动化。</p>
     */
    @PostMapping("/delete")
    @ResponseBody
    public Map<String, Object> deletePlan(@RequestParam String title,
                                           @RequestParam(defaultValue = "api") String mode) {
        // 优先 API 模式
        if ("api".equals(mode)) {
            String planId = planApiService.findPlanIdByTitle(title);
            if (planId != null) {
                IfdianPlanApiService.ApiResult apiResult = planApiService.deletePlan(planId);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", apiResult.isSuccess());
                response.put("message", apiResult.getMessage());
                response.put("needLogin", apiResult.isNeedLogin());
                response.put("mode", "api");
                if (apiResult.isSuccess()) {
                    return response;
                }
                if (apiResult.isNeedLogin()) {
                    return response;
                }
                log.warn("[Plans] API delete failed ({}), falling back to browser", apiResult.getMessage());
            } else {
                log.warn("[Plans] Plan not found by title '{}' via API, falling back to browser", title);
            }
        }

        // 降级：浏览器自动化
        BrowserAutomationService.PlanCreationResult result =
                browserAutomation.deletePlan(title);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.isSuccess());
        response.put("message", result.getMessage());
        response.put("needLogin", result.isNeedLogin());
        response.put("mode", "browser");
        if (result.getScreenshotBase64() != null) {
            response.put("screenshotBase64", result.getScreenshotBase64());
        }
        return response;
    }

    /**
     * 切换方案隐藏/显示 — AJAX 接口
     *
     * <p>优先使用伪 API 模式，失败时降级为浏览器自动化。</p>
     */
    @PostMapping("/toggle-hide")
    @ResponseBody
    public Map<String, Object> toggleHidePlan(@RequestParam String title,
                                               @RequestParam(defaultValue = "api") String mode) {
        // 优先 API 模式
        if ("api".equals(mode)) {
            String planId = planApiService.findPlanIdByTitle(title);
            if (planId != null) {
                IfdianPlanApiService.ApiResult apiResult = planApiService.togglePlanVisibility(planId);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", apiResult.isSuccess());
                response.put("message", apiResult.getMessage());
                response.put("needLogin", apiResult.isNeedLogin());
                response.put("mode", "api");
                if (apiResult.isSuccess()) {
                    return response;
                }
                if (apiResult.isNeedLogin()) {
                    return response;
                }
                log.warn("[Plans] API toggle failed ({}), falling back to browser", apiResult.getMessage());
            } else {
                log.warn("[Plans] Plan not found by title '{}' via API, falling back to browser", title);
            }
        }

        // 降级：浏览器自动化
        BrowserAutomationService.PlanCreationResult result =
                browserAutomation.toggleHidePlan(title);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.isSuccess());
        response.put("message", result.getMessage());
        response.put("needLogin", result.isNeedLogin());
        response.put("mode", "browser");
        if (result.getScreenshotBase64() != null) {
            response.put("screenshotBase64", result.getScreenshotBase64());
        }
        return response;
    }

    /**
     * 发现赞助方案 — AJAX 接口，从爱发电 API 订单中提取方案列表
     */
    @PostMapping("/discover")
    @ResponseBody
    public Map<String, Object> discover(@RequestParam(defaultValue = "3") int maxPages,
                                         @RequestParam(defaultValue = "100") int perPage) {
        List<Map<String, String>> plans = cachedApi.discoverPlans(maxPages, perPage);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", String.format("发现 %d 个赞助方案（扫描了最多 %d 页订单）", plans.size(), maxPages));
        result.put("plans", plans);
        result.put("planCount", plans.size());
        return result;
    }

    /**
     * 查询方案详情 — AJAX 接口，从爱发电 API 查询单个方案的详细信息
     */
    @PostMapping("/query")
    @ResponseBody
    public Map<String, Object> queryPlan(@RequestParam String planId) {
        JsonNode apiResult = cachedApi.queryPlan(planId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (apiResult == null) {
            result.put("success", false);
            result.put("message", "API 调用失败，请检查网络连接和配置");
            return result;
        }
        result.put("success", apiResult.path("ec").asInt() == 200);
        result.put("ec", apiResult.path("ec").asInt());
        result.put("em", apiResult.path("em").asText(""));

        JsonNode data = apiResult.path("data");
        if (data.isTextual()) {
            try {
                data = objectMapper.readTree(data.asText());
            } catch (Exception e) {
                log.warn("[Ifdain] queryPlan: data is text but not valid JSON");
            }
        }
        if (data.isObject()) {
            result.put("data", objectMapper.convertValue(data,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        }
        result.put("rawJson", apiResult.toPrettyString());
        return result;
    }

    private boolean isApiConfigured() {
        String userId = configService.getOrDefault(SystemConfigService.KEY_USER_ID, "");
        String token = configService.getOrDefault(SystemConfigService.KEY_API_TOKEN, "");
        return !userId.isEmpty() && !token.isEmpty();
    }
}
