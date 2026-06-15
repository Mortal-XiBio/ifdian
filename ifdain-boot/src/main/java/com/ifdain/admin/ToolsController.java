package com.ifdain.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifdain.service.AfdianApiClient;
import com.ifdain.service.IfdianWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API 工具控制器
 *
 * <p>提供管理后台中直接调用爱发电 API 的调试工具页面。</p>
 */
@Slf4j
@Controller
@RequestMapping("${ifdain.admin.base-path}/tools")
@RequiredArgsConstructor
public class ToolsController {

    private final AfdianApiClient apiClient;
    private final IfdianWebhookService webhookService;
    private final AdminProperties adminProperties;
    private final SystemConfigService configService;
    private final ObjectMapper objectMapper;

    /**
     * API 工具主页
     */
    @GetMapping
    public String tools(Model model) {
        model.addAttribute("basePath", adminProperties.getBasePath());
        model.addAttribute("apiConfigured", isApiConfigured());
        return "admin/tools";
    }

    /**
     * 查询订单
     */
    @PostMapping("/query-order")
    public String queryOrder(@RequestParam(required = false) String outTradeNo,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "20") int perPage,
                             Model model) {
        model.addAttribute("basePath", adminProperties.getBasePath());
        model.addAttribute("apiConfigured", isApiConfigured());

        if (outTradeNo != null && !outTradeNo.isEmpty()) {
            List<String> nos = new ArrayList<>();
            nos.add(outTradeNo.trim());
            JsonNode result = apiClient.queryOrdersByNos(nos);
            model.addAttribute("queryResult", formatResult(result));
            model.addAttribute("queryType", "orders");
        } else {
            JsonNode result = apiClient.queryOrders(page, perPage);
            model.addAttribute("queryResult", formatResult(result));
            model.addAttribute("queryType", "orders");
        }
        model.addAttribute("formOutTradeNo", outTradeNo);
        model.addAttribute("formPage", page);
        model.addAttribute("formPerPage", perPage);
        return "admin/tools";
    }

    /**
     * 查询赞助者
     */
    @PostMapping("/query-sponsor")
    public String querySponsor(@RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int perPage,
                               Model model) {
        model.addAttribute("basePath", adminProperties.getBasePath());
        model.addAttribute("apiConfigured", isApiConfigured());

        JsonNode result = apiClient.querySponsors(page, perPage);
        model.addAttribute("queryResult", formatResult(result));
        model.addAttribute("queryType", "sponsors");
        model.addAttribute("formPage", page);
        model.addAttribute("formPerPage", perPage);
        return "admin/tools";
    }

    /**
     * 查询方案详情
     */
    @PostMapping("/query-plan")
    public String queryPlan(@RequestParam String planId, Model model) {
        model.addAttribute("basePath", adminProperties.getBasePath());
        model.addAttribute("apiConfigured", isApiConfigured());

        JsonNode result = apiClient.queryPlan(planId);
        model.addAttribute("queryResult", formatResult(result));
        model.addAttribute("queryType", "plan");
        model.addAttribute("formPlanId", planId);
        return "admin/tools";
    }

    /**
     * 发送私信
     */
    @PostMapping("/send-msg")
    public String sendMsg(@RequestParam String recipient,
                          @RequestParam String content,
                          Model model) {
        model.addAttribute("basePath", adminProperties.getBasePath());
        model.addAttribute("apiConfigured", isApiConfigured());

        JsonNode result = apiClient.sendPrivateMessage(recipient, content);
        model.addAttribute("queryResult", formatResult(result));
        model.addAttribute("queryType", "message");
        model.addAttribute("formRecipient", recipient);
        model.addAttribute("formContent", content);
        return "admin/tools";
    }

    /**
     * 拉取订单同步 — 从爱发电 API 主动拉取订单并保存到本地数据库
     *
     * <p>遍历所有分页，将不存在于本地的订单逐个入库。
     * 适用于：Webhook 漏单补偿、历史数据初始化、数据校验。</p>
     */
    @PostMapping("/sync-orders")
    public String syncOrders(@RequestParam(defaultValue = "1") int startPage,
                             @RequestParam(defaultValue = "100") int perPage,
                             @RequestParam(defaultValue = "0") int maxPages,
                             Model model) {
        model.addAttribute("basePath", adminProperties.getBasePath());
        model.addAttribute("apiConfigured", isApiConfigured());

        Map<String, Object> result = new LinkedHashMap<>();
        int totalFetched = 0;
        int newSaved = 0;
        int skipped = 0;
        int pagesProcessed = 0;
        List<String> errors = new ArrayList<>();

        int page = startPage;
        try {
            while (true) {
                JsonNode apiResult = apiClient.queryOrders(page, perPage);
                if (apiResult == null || apiResult.path("ec").asInt() != 200) {
                    String em = apiResult != null ? apiResult.path("em").asText("") : "no response";
                    errors.add("第 " + page + " 页请求失败: " + em);
                    break;
                }

                JsonNode list = apiResult.path("data").path("list");
                int totalPage = apiResult.path("data").path("total_page").asInt();

                if (!list.isArray() || list.isEmpty()) {
                    break;
                }

                for (JsonNode orderNode : list) {
                    totalFetched++;
                    try {
                        String rawJson = objectMapper.writeValueAsString(orderNode);
                        var saved = webhookService.saveOrderFromApi(orderNode, rawJson);
                        if (saved != null) {
                            newSaved++;
                        } else {
                            skipped++;
                        }
                    } catch (Exception e) {
                        errors.add("保存订单失败: " + e.getMessage());
                    }
                }

                pagesProcessed++;
                log.info("[Ifdain] Sync page {} done, fetched={}, new={}, skip={}",
                        page, totalFetched, newSaved, skipped);

                // 到达最后一页或达到最大页数
                if (page >= totalPage || (maxPages > 0 && pagesProcessed >= maxPages)) {
                    break;
                }
                page++;

                // 避免请求过快
                Thread.sleep(200);
            }
        } catch (Exception e) {
            log.error("[Ifdain] Sync orders failed", e);
            errors.add("同步异常: " + e.getMessage());
        }

        result.put("error", false);
        result.put("message", String.format(
                "同步完成: 共拉取 %d 条订单, 新增入库 %d 条, 跳过已有 %d 条, 处理 %d 页",
                totalFetched, newSaved, skipped, pagesProcessed));
        result.put("total_fetched", totalFetched);
        result.put("new_saved", newSaved);
        result.put("skipped", skipped);
        result.put("pages_processed", pagesProcessed);
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }

        model.addAttribute("queryResult", result);
        model.addAttribute("queryType", "sync");
        model.addAttribute("formPage", startPage);
        model.addAttribute("formPerPage", perPage);
        model.addAttribute("formMaxPages", maxPages);
        return "admin/tools";
    }

    /**
     * Ping 测试
     */
    @PostMapping("/ping")
    public String ping(Model model) {
        model.addAttribute("basePath", adminProperties.getBasePath());
        model.addAttribute("apiConfigured", isApiConfigured());

        boolean ok = apiClient.ping();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", ok);
        result.put("message", ok ? "签名验证通过，API 连接正常" : "签名验证失败，请检查 User ID 和 Token");
        model.addAttribute("queryResult", result);
        model.addAttribute("queryType", "ping");
        return "admin/tools";
    }

    /**
     * 发现赞助方案 — 从近期订单中提取去重方案列表
     *
     * <p>爱发电 API 没有「列出所有方案」接口，因此遍历近期订单来发现方案。
     * 扫描完成后展示方案列表，点击可自动填入方案 ID 进行查询。</p>
     */
    @PostMapping("/discover-plans")
    public String discoverPlans(@RequestParam(defaultValue = "3") int maxPages,
                                 @RequestParam(defaultValue = "100") int perPage,
                                 Model model) {
        model.addAttribute("basePath", adminProperties.getBasePath());
        model.addAttribute("apiConfigured", isApiConfigured());

        List<Map<String, String>> plans = apiClient.discoverPlans(maxPages, perPage);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", false);
        result.put("message", String.format("发现 %d 个赞助方案（扫描了最多 %d 页订单）", plans.size(), maxPages));
        result.put("plans", plans);
        result.put("planCount", plans.size());

        model.addAttribute("queryResult", result);
        model.addAttribute("queryType", "plans");
        return "admin/tools";
    }

    // ===== 辅助 =====

    private boolean isApiConfigured() {
        String userId = configService.getOrDefault(SystemConfigService.KEY_USER_ID, "");
        String token = configService.getOrDefault(SystemConfigService.KEY_API_TOKEN, "");
        return !userId.isEmpty() && !token.isEmpty();
    }

    private Map<String, Object> formatResult(JsonNode result) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (result == null) {
            map.put("error", true);
            map.put("message", "API 调用失败，请检查网络连接和配置");
            return map;
        }
        map.put("ec", result.path("ec").asInt());
        map.put("em", result.path("em").asText(""));
        map.put("error", result.path("ec").asInt() != 200);

        JsonNode data = result.path("data");
        // 部分 API 版本返回 data 为 JSON 字符串，需先解析
        if (data.isTextual()) {
            try {
                data = objectMapper.readTree(data.asText());
            } catch (Exception e) {
                log.warn("[Ifdain] formatResult: data is text but not valid JSON, keeping as-is");
            }
        }
        if (data.has("list")) {
            // 将 JsonNode 列表转为 List<Map>，使 Thymeleaf 能通过点号访问属性
            JsonNode listNode = data.path("list");
            List<Map<String, Object>> list = new ArrayList<>();
            List<String> headers = new ArrayList<>();

            if (listNode != null && listNode.isArray() && !listNode.isEmpty()) {
                for (JsonNode item : listNode) {
                    try {
                        Map<String, Object> itemMap = objectMapper.convertValue(item,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                        list.add(itemMap);
                        // 以第一个元素的 key 作为表头（保留顺序）
                        if (headers.isEmpty()) {
                            headers.addAll(itemMap.keySet());
                        }
                    } catch (Exception e) {
                        log.warn("[Ifdain] formatResult: failed to convert item, skipping. error={}", e.getMessage());
                    }
                }
                map.put("total_count", data.path("total_count").asInt());
                map.put("total_page", data.path("total_page").asInt());
            } else {
                log.warn("[Ifdain] formatResult: data.list is null, not an array, or empty. listNodeType={}",
                        listNode != null ? listNode.getNodeType() : "null");
            }
            map.put("list", list);
            map.put("listHeaders", headers);
            log.info("[Ifdain] formatResult: list.size={}, total_count={}, headers={}",
                    list.size(), map.get("total_count"), headers);
        } else if (data.isObject() || data.isArray()) {
            // 单个对象/数组响应（如方案详情），同样转为 Map 以便模板访问
            if (data.isObject()) {
                map.put("data", objectMapper.convertValue(data,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            } else {
                map.put("data", data);
            }
        }

        // JSON 原文（用于调试）
        map.put("rawJson", result.toPrettyString());
        return map;
    }
}
