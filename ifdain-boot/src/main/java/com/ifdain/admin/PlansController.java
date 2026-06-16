package com.ifdain.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifdain.automation.BrowserAutomationService;
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
    private final ObjectMapper objectMapper;
    private final BrowserAutomationService browserAutomation;

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
        // 推荐方案: "iframe" 或 "browser"
        result.put("recommendedMode", cookieConfigured ? "browser" : "iframe");
        return result;
    }

    /**
     * 创建赞助方案 — AJAX 接口，通过浏览器自动化在爱发电官网创建
     *
     * <p>这是 iframe 方案的 fallback。前端先尝试 iframe，被阻止时
     * 收集用户输入的方案参数调用此接口。</p>
     */
    @PostMapping("/create")
    @ResponseBody
    public Map<String, Object> createPlan(
            @RequestParam String title,
            @RequestParam(required = false) Double price,
            @RequestParam(required = false) String description) {

        if (price != null && (price < 5 || price > 20000)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "价格必须在 5 ~ 20000 元之间");
            return err;
        }

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
        if (creationResult.getScreenshotBase64() != null) {
            result.put("screenshotBase64", creationResult.getScreenshotBase64());
        }
        return result;
    }

    /**
     * 删除赞助方案 — AJAX 接口，通过浏览器自动化删除方案
     */
    @PostMapping("/delete")
    @ResponseBody
    public Map<String, Object> deletePlan(@RequestParam String title) {
        BrowserAutomationService.PlanCreationResult result =
                browserAutomation.deletePlan(title);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.isSuccess());
        response.put("message", result.getMessage());
        response.put("needLogin", result.isNeedLogin());
        if (result.getScreenshotBase64() != null) {
            response.put("screenshotBase64", result.getScreenshotBase64());
        }
        return response;
    }

    /**
     * 切换方案隐藏/显示 — AJAX 接口
     */
    @PostMapping("/toggle-hide")
    @ResponseBody
    public Map<String, Object> toggleHidePlan(@RequestParam String title) {
        BrowserAutomationService.PlanCreationResult result =
                browserAutomation.toggleHidePlan(title);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.isSuccess());
        response.put("message", result.getMessage());
        response.put("needLogin", result.isNeedLogin());
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
        List<Map<String, String>> plans = apiClient.discoverPlans(maxPages, perPage);
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
        JsonNode apiResult = apiClient.queryPlan(planId);
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
