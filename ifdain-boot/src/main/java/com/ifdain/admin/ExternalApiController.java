package com.ifdain.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.ifdain.entity.IfdianOrder;
import com.ifdain.entity.OrderStatus;
import com.ifdain.entity.PaymentRequest;
import com.ifdain.entity.PaymentRequestStatus;
import com.ifdain.repository.IfdianOrderRepository;
import com.ifdain.repository.PaymentRequestRepository;
import com.ifdain.service.AfdianApiClient;
import com.ifdain.service.CallbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 对外 REST API 控制器
 *
 * <p>供其他项目接入使用，提供 VIP/订阅状态验证、订单查询、赞助者查询等功能。
 * 所有接口需通过 {@code X-Api-Key} 请求头或 {@code api_key} 查询参数进行认证。</p>
 *
 * <h3>认证方式</h3>
 * <ul>
 *   <li>请求头: {@code X-Api-Key: your-api-key}</li>
 *   <li>查询参数: {@code ?api_key=your-api-key}</li>
 * </ul>
 *
 * <h3>响应格式</h3>
 * <pre>{@code
 * {
 *   "code": 200,
 *   "message": "success",
 *   "data": { ... }
 * }
 * }</pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/external")
@RequiredArgsConstructor
public class ExternalApiController {

    private final IfdianOrderRepository orderRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final AfdianApiClient apiClient;
    private final SystemConfigService configService;
    private final CallbackService callbackService;

    // ==================== 订阅验证 ====================

    /**
     * 验证用户的订阅/VIP 状态
     *
     * <p>查询用户在指定方案下是否有有效的付费订单。
     * 验证逻辑：检查是否存在该用户在该方案下的已支付订单。</p>
     *
     * @param userId   爱发电用户ID (必填)
     * @param planId   方案ID (可选，不传则查询所有方案)
     * @param userPrivateId 用户唯一标识 OAuth2 (可选)
     * @return 订阅状态信息
     */
    @GetMapping("/verify-subscription")
    public ResponseEntity<Map<String, Object>> verifySubscription(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String planId,
            @RequestParam(required = false) String userPrivateId,
            @RequestParam(required = false) String apiKey,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKeyHeader) {

        if (!authenticate(apiKey, apiKeyHeader)) {
            return error(401, "invalid api key");
        }

        if (userId == null && userPrivateId == null) {
            return error(400, "userId or userPrivateId is required");
        }

        // 查询已支付订单
        List<IfdianOrder> orders;
        if (userId != null) {
            orders = orderRepository.findByUserId(userId);
        } else {
            // 按 userPrivateId 查询需新增 repository 方法
            orders = orderRepository.findByUserId(userPrivateId);
        }

        // 过滤已支付订单
        List<IfdianOrder> paidOrders = orders.stream()
                .filter(o -> o.getStatus() == OrderStatus.PAID)
                .collect(Collectors.toList());

        // 如果指定了方案，再过滤
        if (planId != null && !planId.isEmpty()) {
            paidOrders = paidOrders.stream()
                    .filter(o -> planId.equals(o.getPlanId()))
                    .collect(Collectors.toList());
        }

        // 计算汇总
        BigDecimal totalPaid = paidOrders.stream()
                .map(IfdianOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Optional<IfdianOrder> latestOrder = paidOrders.stream()
                .max(Comparator.comparing(o -> o.getReceivedAt() != null ? o.getReceivedAt() : LocalDateTime.MIN));

        Map<String, Object> data = new HashMap<>();
        data.put("subscribed", !paidOrders.isEmpty());
        data.put("active_plans", paidOrders.stream()
                .map(IfdianOrder::getPlanId)
                .distinct()
                .collect(Collectors.toList()));
        data.put("total_paid", totalPaid.doubleValue());
        data.put("order_count", paidOrders.size());
        data.put("latest_order_time", latestOrder.map(o -> o.getReceivedAt() != null
                ? o.getReceivedAt().toString() : null).orElse(null));
        data.put("queried_user_id", userId != null ? userId : userPrivateId);

        return success(data);
    }

    // ==================== 订单查询 ====================

    /**
     * 查询订单
     *
     * <p>支持按订单号、用户ID、自定义订单ID查询，也可分页获取全部订单。</p>
     *
     * @param outTradeNo    爱发电订单号 (可选)
     * @param userId        爱发电用户ID (可选)
     * @param customOrderId 自定义订单ID (可选)
     * @param page          页码，从0开始 (默认0)
     * @param size          每页条数 (默认20)
     */
    @GetMapping("/orders")
    public ResponseEntity<Map<String, Object>> queryOrders(
            @RequestParam(required = false) String outTradeNo,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String customOrderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String apiKey,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKeyHeader) {

        if (!authenticate(apiKey, apiKeyHeader)) {
            return error(401, "invalid api key");
        }

        // 按订单号精确查询
        if (outTradeNo != null && !outTradeNo.isEmpty()) {
            Optional<IfdianOrder> orderOpt = orderRepository.findByOutTradeNo(outTradeNo);
            if (orderOpt.isEmpty()) {
                return success(Collections.singletonMap("order", null));
            }
            return success(Collections.singletonMap("order", toOrderMap(orderOpt.get())));
        }

        // 按用户ID查询
        if (userId != null && !userId.isEmpty()) {
            List<IfdianOrder> orders = orderRepository.findByUserId(userId);
            return success(Map.of(
                    "orders", orders.stream().map(this::toOrderMap).collect(Collectors.toList()),
                    "total", orders.size()
            ));
        }

        // 按自定义订单ID查询
        if (customOrderId != null && !customOrderId.isEmpty()) {
            List<IfdianOrder> orders = orderRepository.findByCustomOrderId(customOrderId);
            return success(Map.of(
                    "orders", orders.stream().map(this::toOrderMap).collect(Collectors.toList()),
                    "total", orders.size()
            ));
        }

        // 分页查询全部
        var orderPage = orderRepository.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(page, size));
        return success(Map.of(
                "orders", orderPage.getContent().stream().map(this::toOrderMap).collect(Collectors.toList()),
                "total", orderPage.getTotalElements(),
                "page", orderPage.getNumber(),
                "total_pages", orderPage.getTotalPages()
        ));
    }

    // ==================== 赞助者查询 ====================

    /**
     * 查询赞助者信息
     *
     * <p>调用爱发电 API 实时查询赞助者详情（包括累计金额、最近赞助时间等）。</p>
     *
     * @param afdianUserId 爱发电用户ID (可选，不传则分页查询全部)
     * @param page         页码 (从1开始，默认1)
     * @param perPage      每页条数 (默认20)
     */
    @GetMapping("/sponsor")
    public ResponseEntity<Map<String, Object>> querySponsor(
            @RequestParam(required = false) String afdianUserId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int perPage,
            @RequestParam(required = false) String apiKey,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKeyHeader) {

        if (!authenticate(apiKey, apiKeyHeader)) {
            return error(401, "invalid api key");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("page", page);
        params.put("per_page", perPage);
        if (afdianUserId != null && !afdianUserId.isEmpty()) {
            params.put("user_id", afdianUserId);
        }

        JsonNode result = apiClient.callApi("/api/open/query-sponsor", params);
        if (result == null) {
            return error(502, "upstream API call failed");
        }

        int ec = result.path("ec").asInt();
        if (ec != 200) {
            return error(ec, result.path("em").asText("upstream error"));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("list", result.path("data").path("list"));
        data.put("total_count", result.path("data").path("total_count").asInt());
        data.put("total_page", result.path("data").path("total_page").asInt());

        return success(data);
    }

    // ==================== 方案查询 ====================

    /**
     * 查询方案详情
     *
     * <p>实时调用爱发电 API 获取方案信息（类型、SKU 列表等）。</p>
     *
     * @param planId 方案ID (必填)
     */
    @GetMapping("/plan")
    public ResponseEntity<Map<String, Object>> queryPlan(
            @RequestParam String planId,
            @RequestParam(required = false) String apiKey,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKeyHeader) {

        if (!authenticate(apiKey, apiKeyHeader)) {
            return error(401, "invalid api key");
        }

        JsonNode result = apiClient.queryPlan(planId);
        if (result == null) {
            return error(502, "upstream API call failed");
        }

        int ec = result.path("ec").asInt();
        if (ec != 200) {
            return error(ec, result.path("em").asText("upstream error"));
        }

        return success(result.path("data"));
    }

    // ==================== 健康检查 ====================

    /**
     * API 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return success(Map.of(
                "status", "UP",
                "service", "ifdain-external-api",
                "timestamp", System.currentTimeMillis()
        ));
    }

    // ==================== 外部支付请求 ====================

    /**
     * 创建支付请求
     *
     * <p>外部程序调用此接口生成爱发电支付链接，并建立回调追踪。
     * 返回的 signatureSecret 需由调用方安全保管，用于验证后续回调签名。</p>
     *
     * <h3>请求体</h3>
     * <pre>{@code
     * {
     *   "userId": "爱发电用户ID (必填)",
     *   "planId": "方案ID (必填)",
     *   "customOrderId": "自定义订单号 (必填, 全局唯一)",
     *   "callbackUrl": "支付成功回调URL (必填)",
     *   "totalAmount": 10.00,
     *   "remark": "备注 (可选)"
     * }
     * }</pre>
     */
    @PostMapping("/payment-request")
    public ResponseEntity<Map<String, Object>> createPaymentRequest(
            @RequestBody Map<String, Object> body,
            @RequestParam(required = false) String apiKey,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKeyHeader) {

        if (!authenticate(apiKey, apiKeyHeader)) {
            return error(401, "invalid api key");
        }

        // 必填参数校验
        String userId = body != null ? (String) body.get("userId") : null;
        String planId = body != null ? (String) body.get("planId") : null;
        String customOrderId = body != null ? (String) body.get("customOrderId") : null;
        String callbackUrl = body != null ? (String) body.get("callbackUrl") : null;

        if (userId == null || userId.isBlank()
                || planId == null || planId.isBlank()
                || customOrderId == null || customOrderId.isBlank()
                || callbackUrl == null || callbackUrl.isBlank()) {
            return error(400, "userId, planId, customOrderId, callbackUrl are all required");
        }

        // 检查 customOrderId 唯一性
        if (paymentRequestRepository.existsByCustomOrderId(customOrderId)) {
            return error(400, "customOrderId already exists: " + customOrderId);
        }

        // 解析可选字段
        BigDecimal totalAmount = null;
        Object amountObj = body.get("totalAmount");
        if (amountObj != null) {
            if (amountObj instanceof Number) {
                totalAmount = BigDecimal.valueOf(((Number) amountObj).doubleValue());
            } else {
                try {
                    totalAmount = new BigDecimal(amountObj.toString());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        String remark = (String) body.get("remark");

        // 生成签名密钥
        String secret = callbackService.generateSecret();

        // 构建 PaymentRequest
        PaymentRequest pr = PaymentRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .userId(userId)
                .planId(planId)
                .customOrderId(customOrderId)
                .callbackUrl(callbackUrl)
                .status(PaymentRequestStatus.PENDING)
                .signatureSecret(secret)
                .totalAmount(totalAmount)
                .remark(remark)
                .build();

        paymentRequestRepository.save(pr);

        log.info("[Ifdain] Payment request created: requestId={}, customOrderId={}, planId={}",
                pr.getRequestId(), customOrderId, planId);

        // 构建响应
        String apiBaseUrl = configService.getOrDefault(SystemConfigService.KEY_API_BASE_URL, "https://ifdian.net");
        String paymentUrl = apiBaseUrl + "/order?plan_id=" + planId;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", pr.getRequestId());
        data.put("paymentUrl", paymentUrl);
        data.put("customOrderId", pr.getCustomOrderId());
        data.put("signatureSecret", secret);
        data.put("status", pr.getStatus().name());
        data.put("createdAt", pr.getCreatedAt() != null ? pr.getCreatedAt().toString() : null);

        return success(data);
    }

    /**
     * 查询支付请求状态
     *
     * <p>外部程序通过 requestId 查询支付请求的当前状态。</p>
     */
    @GetMapping("/payment-request/{requestId}")
    public ResponseEntity<Map<String, Object>> getPaymentRequest(
            @PathVariable String requestId,
            @RequestParam(required = false) String apiKey,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKeyHeader) {

        if (!authenticate(apiKey, apiKeyHeader)) {
            return error(401, "invalid api key");
        }

        Optional<PaymentRequest> opt = paymentRequestRepository.findByRequestId(requestId);
        if (opt.isEmpty()) {
            return error(404, "payment request not found: " + requestId);
        }

        PaymentRequest pr = opt.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", pr.getRequestId());
        data.put("customOrderId", pr.getCustomOrderId());
        data.put("status", pr.getStatus().name());
        data.put("outTradeNo", pr.getOutTradeNo());
        data.put("callbackRetryCount", pr.getCallbackRetryCount());
        data.put("callbackLastAttempt", pr.getCallbackLastAttempt() != null
                ? pr.getCallbackLastAttempt().toString() : null);
        data.put("callbackError", pr.getCallbackError());
        data.put("createdAt", pr.getCreatedAt() != null ? pr.getCreatedAt().toString() : null);
        data.put("updatedAt", pr.getUpdatedAt() != null ? pr.getUpdatedAt().toString() : null);

        return success(data);
    }

    /**
     * 手动重试支付回调
     *
     * <p>当回调失败时，外部程序可通过此端点手动触发重试。
     * 仅当 PaymentRequest 状态为 PAID（已支付但回调未成功）时有效。</p>
     */
    @PostMapping("/payment-request/{requestId}/retry-callback")
    public ResponseEntity<Map<String, Object>> retryPaymentCallback(
            @PathVariable String requestId,
            @RequestParam(required = false) String apiKey,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKeyHeader) {

        if (!authenticate(apiKey, apiKeyHeader)) {
            return error(401, "invalid api key");
        }

        Optional<PaymentRequest> opt = paymentRequestRepository.findByRequestId(requestId);
        if (opt.isEmpty()) {
            return error(404, "payment request not found: " + requestId);
        }

        PaymentRequest pr = opt.get();
        if (pr.getStatus() != PaymentRequestStatus.PAID) {
            return error(400, "payment request status is " + pr.getStatus().name()
                    + ", only PAID requests can retry callback");
        }

        boolean success = callbackService.retryCallback(pr);
        if (success) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("requestId", pr.getRequestId());
            data.put("status", pr.getStatus().name());
            data.put("message", "callback succeeded");
            return success(data);
        } else {
            return error(502, "callback still failed: " + pr.getCallbackError());
        }
    }

    // ==================== 内部辅助 ====================

    /**
     * API Key 认证
     */
    private boolean authenticate(String apiKeyParam, String apiKeyHeader) {
        String providedKey = apiKeyHeader != null ? apiKeyHeader : apiKeyParam;
        if (providedKey == null || providedKey.isEmpty()) {
            return false;
        }
        String expectedKey = configService.getOrDefault("ifdain.external_api_key", "");
        if (expectedKey.isEmpty()) {
            log.warn("[Ifdain] External API key not configured, denying request");
            return false;
        }
        return providedKey.equals(expectedKey);
    }

    private ResponseEntity<Map<String, Object>> success(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("message", "success");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> error(int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", null);
        return ResponseEntity.status(code >= 500 ? 502 : 400).body(body);
    }

    /**
     * 将订单实体转换为安全的 Map（隐藏敏感字段）
     */
    private Map<String, Object> toOrderMap(IfdianOrder order) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", order.getId());
        map.put("out_trade_no", order.getOutTradeNo());
        map.put("custom_order_id", order.getCustomOrderId());
        map.put("user_id", order.getUserId());
        map.put("user_private_id", order.getUserPrivateId());
        map.put("plan_id", order.getPlanId());
        map.put("sponsor_month", order.getSponsorMonth());
        map.put("total_amount", order.getTotalAmount() != null ? order.getTotalAmount().doubleValue() : null);
        map.put("show_amount", order.getShowAmount() != null ? order.getShowAmount().doubleValue() : null);
        map.put("status", order.getStatus() != null ? order.getStatus().getValue() : null);
        map.put("status_text", order.getStatus() != null ? order.getStatus().name() : null);
        map.put("remark", order.getRemark());
        map.put("product_type", order.getProductType());
        map.put("discount", order.getDiscount() != null ? order.getDiscount().doubleValue() : null);
        map.put("received_at", order.getReceivedAt() != null ? order.getReceivedAt().toString() : null);
        map.put("created_at", order.getCreatedAt() != null ? order.getCreatedAt().toString() : null);
        return map;
    }
}
