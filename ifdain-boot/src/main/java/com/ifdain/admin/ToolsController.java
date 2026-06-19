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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("out_trade_no", "订单号"),
            Map.entry("custom_order_id", "自定义订单ID"),
            Map.entry("user_id", "用户ID"),
            Map.entry("user_private_id", "用户ID"),
            Map.entry("user", "赞助者"),
            Map.entry("sponsor_plans", "赞助方案"),
            Map.entry("current_plan", "当前方案"),
            Map.entry("all_sum_amount", "累计金额"),
            Map.entry("show_amount", "单价"),
            Map.entry("first_pay_time", "首次赞助"),
            Map.entry("last_pay_time", "最近赞助"),
            Map.entry("last_pay_amount", "最近金额"),
            Map.entry("plan_id", "方案ID"),
            Map.entry("plan_title", "方案名称"),
            Map.entry("total_amount", "订单金额"),
            Map.entry("status", "状态"),
            Map.entry("remark", "备注"),
            Map.entry("redeem_id", "兑换ID"),
            Map.entry("discount", "减免"),
            Map.entry("create_time", "创建时间"),
            Map.entry("pay_time", "支付时间"),
            Map.entry("product_type", "类型"),
            Map.entry("name", "名称"),
            Map.entry("price", "价格"),
            Map.entry("desc", "描述"),
            Map.entry("email", "邮箱"),
            Map.entry("method", "支付方式"),
            Map.entry("transaction_id", "交易号"),
            Map.entry("address_person", "收件人"),
            Map.entry("address_phone", "收件电话"),
            Map.entry("address_address", "收件地址"),
            Map.entry("expire_time", "到期时间"),
            Map.entry("sku_detail", "SKU详情"),
            Map.entry("sku_id", "SKU ID"),
            Map.entry("month", "购买月数"),
            Map.entry("pay_month", "付费月数"),
            Map.entry("show_aff", "展示推荐"),
            Map.entry("exchange_rate", "汇率"),
            Map.entry("stock", "库存"),
            Map.entry("count", "数量"),
            Map.entry("pic", "图片"),
            Map.entry("avatar", "头像"),
            Map.entry("internal_info", "内部信息"),
            Map.entry("independent", "独立方案"),
            Map.entry("permanent", "永久方案"),
            Map.entry("reply_content", "自动回复"),
            Map.entry("replay_random_content", "随机回复")
    );

    private String displayName(String fieldName) {
        return FIELD_LABELS.getOrDefault(fieldName, fieldName);
    }

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
        Map<String, Object> formatted = formatResult(result);
        formatPlanAsTables(formatted, result);
        model.addAttribute("queryResult", formatted);
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
        log.info("[Ifdain] discover-plans: found {} plans: {}", plans.size(), plans);

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
                                new com.fasterxml.jackson.core.type.TypeReference<>() {});
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

            // 生成 rows (List<List<Object>>), 模板用 row[rowIdx] 索引访问
            // 避免 SpEL 中 item[header] 变量键访问可能失效的问题
            List<List<Object>> rows = new ArrayList<>();
            for (Map<String, Object> itemMap : list) {
                List<Object> row = new ArrayList<>();
                for (String h : headers) {
                    Object val = itemMap.get(h);
                    if (val != null && h.endsWith("_time") && val instanceof Number) {
                        val = formatTimestamp(((Number) val).longValue());
                    }
                    row.add(val != null ? toReadableString(val) : "");
                }
                rows.add(row);
            }
            map.put("rows", rows);

            // 中文表头
            List<String> displayHeaders = new ArrayList<>();
            for (String h : headers) {
                displayHeaders.add(displayName(h));
            }
            map.put("displayHeaders", displayHeaders);

            // 提取每行的 user_id 和 user_name (赞助者表格用于发私信按钮)
            List<String> rowUserIds = new ArrayList<>();
            List<String> rowUserNames = new ArrayList<>();
            for (Map<String, Object> itemMap : list) {
                Object userObj = itemMap.get("user");
                if (userObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> userMap = (Map<String, Object>) userObj;
                    rowUserIds.add(String.valueOf(userMap.getOrDefault("user_id", "")));
                    rowUserNames.add(String.valueOf(userMap.getOrDefault("name", "")));
                } else {
                    rowUserIds.add("");
                    rowUserNames.add("");
                }
            }
            map.put("rowUserIds", rowUserIds);
            map.put("rowUserNames", rowUserNames);

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

    /**
     * 将 Unix 时间戳(秒)转换为可读日期字符串
     */
    private String formatTimestamp(long epochSecond) {
        if (epochSecond <= 0) return "";
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 将嵌套对象/数组转为表格可读字符串
     *
     * <ul>
     *   <li>基本类型 → 直接 toString</li>
     *   <li>Map 含 name → 取 name (如 user, plan)</li>
     *   <li>Map 含 user_id 但无 name → user_id 前8位</li>
     *   <li>List&lt;Map&gt; → 逗号分隔各元素的 name</li>
     *   <li>其他复杂对象 → 紧凑 JSON (截断80字符)</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private String toReadableString(Object val) {
        if (val == null) return "";
        if (val instanceof String || val instanceof Number || val instanceof Boolean) {
            String s = val.toString();
            return s.length() > 100 ? s.substring(0, 97) + "..." : s;
        }
        if (val instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) val;
            // user 对象: 优先取 name
            if (m.containsKey("name")) {
                String name = String.valueOf(m.get("name"));
                if (!name.isEmpty()) return name;
            }
            // 有 user_id 但无 name
            if (m.containsKey("user_id")) {
                String uid = String.valueOf(m.get("user_id"));
                return uid.length() > 8 ? uid.substring(0, 8) + "…" : uid;
            }
            // 兜底: 紧凑 JSON
            try {
                String json = objectMapper.writeValueAsString(val);
                return json.length() > 80 ? json.substring(0, 77) + "..." : json;
            } catch (Exception e) {
                return val.toString();
            }
        }
        if (val instanceof List<?> list) {
            if (list.isEmpty()) return "";
            if (list.getFirst() instanceof Map) {
                // 方案/对象列表: 显示数量而非展开全部对象
                return list.size() + "个方案";
            }
            return list.toString();
        }
        return val.toString();
    }

    /**
     * 将方案详情的 API 响应解析为结构化表格数据
     *
     * <p>顶层简单字段 → kvTable (属性/值 两列表格);
     * 嵌套数组字段 → subTables (各数组独立表格, 如 sku_list)。</p>
     */
    private void formatPlanAsTables(Map<String, Object> formatted, JsonNode rawResult) {
        if (rawResult == null) return;
        JsonNode data = rawResult.path("data");
        if (data.isTextual()) {
            try {
                data = objectMapper.readTree(data.asText());
            } catch (Exception e) {
                return;
            }
        }
        if (!data.isObject()) return;

        // 解包: 如果 data 只有一个 key 且其值是对象(非数组), 则进入该对象
        // 例如 {"plan": {...}} → 直接使用内层 {...}
        JsonNode planData = data;
        if (planData.size() == 1) {
            JsonNode inner = planData.iterator().next();
            if (inner.isObject() && !inner.isArray()) {
                planData = inner;
            }
        }

        // 顶层 kv 表
        List<String> kvHeaders = List.of("属性", "值");
        List<List<String>> kvRows = new ArrayList<>();
        List<Map<String, Object>> subTables = new ArrayList<>();

        java.util.Iterator<Map.Entry<String, JsonNode>> fields = planData.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isArray() && !value.isEmpty() && value.get(0).isObject()) {
                // 嵌套数组 → 独立子表格
                List<String> subHeaders = new ArrayList<>();
                // 第一遍: 收集所有列名
                for (JsonNode item : value) {
                    item.fieldNames().forEachRemaining(fname -> {
                        if (!subHeaders.contains(fname)) {
                            subHeaders.add(fname);
                        }
                    });
                }
                // 第二遍: 按统一列顺序填充每行
                List<List<String>> alignedRows = new ArrayList<>();
                for (JsonNode item : value) {
                    List<String> row = new ArrayList<>();
                    for (String h : subHeaders) {
                        JsonNode v = item.get(h);
                        if (v == null || v.isNull()) {
                            row.add("");
                        } else if (h.endsWith("_time") && v.isNumber()) {
                            row.add(formatTimestamp(v.asLong()));
                        } else {
                            row.add(v.isValueNode() ? v.asText() : v.toString());
                        }
                    }
                    alignedRows.add(row);
                }
                // 子表格表头翻译为中文
                List<String> displaySubHeaders = new ArrayList<>();
                for (String h : subHeaders) {
                    displaySubHeaders.add(displayName(h));
                }
                Map<String, Object> subTable = new LinkedHashMap<>();
                subTable.put("title", displayName(key));
                subTable.put("headers", displaySubHeaders);
                subTable.put("rows", alignedRows);
                subTables.add(subTable);

                // kv 表中仅显示数量摘要
                kvRows.add(List.of(displayName(key), value.size() + "条记录"));
            } else if (value.isObject()) {
                // 嵌套对象: 展开为多行 key-value
                value.fields().forEachRemaining(f -> {
                    String subKey = key + "." + f.getKey();
                    String displayVal = f.getValue().isValueNode() ? f.getValue().asText() : f.getValue().toString();
                    if (displayVal.length() > 200) displayVal = displayVal.substring(0, 197) + "...";
                    kvRows.add(List.of(displayName(subKey), displayVal));
                });
            } else {
                // 简单值字段
                String displayVal;
                if (key.endsWith("_time") && value.isNumber()) {
                    displayVal = formatTimestamp(value.asLong());
                } else {
                    displayVal = value.isValueNode() ? value.asText() : value.toString();
                }
                if (displayVal.length() > 200) displayVal = displayVal.substring(0, 197) + "...";
                kvRows.add(List.of(displayName(key), displayVal));
            }
        }

        Map<String, Object> kvTable = new LinkedHashMap<>();
        kvTable.put("headers", kvHeaders);
        kvTable.put("rows", kvRows);

        formatted.put("planTables", true);
        formatted.put("planKvTable", kvTable);
        formatted.put("planSubTables", subTables);
    }
}
