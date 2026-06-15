package com.ifdain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifdain.config.IfdainProperties;
import com.ifdain.entity.IfdianOrder;
import com.ifdain.entity.OrderStatus;
import com.ifdain.repository.IfdianOrderRepository;
import com.ifdain.util.AfdianSignatureUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 爱发电 Webhook 接收与处理服务
 *
 * <p>负责接收爱发电推送的订单通知，验签、持久化，并触发业务处理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IfdianWebhookService {

    private final IfdianOrderRepository orderRepository;
    private final IfdainProperties properties;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final CallbackService callbackService;

    /**
     * 处理 Webhook 推送数据
     *
     * @param rawBody 原始请求 body (JSON 字符串)
     * @param signHeader 可选: 签名头 (X-Signature 或类似)
     * @return 处理结果
     */
    @Transactional
    public WebhookResult handleWebhook(String rawBody, String signHeader) {
        try {
            // 1. 解析顶层 JSON
            JsonNode root = objectMapper.readTree(rawBody);
            int ec = root.path("ec").asInt();
            String em = root.path("em").asText();

            if (ec != 200) {
                log.warn("[Ifdain] Webhook non-200 ec={} em={}", ec, em);
                return WebhookResult.fail("non-200 response from afdian");
            }

            JsonNode dataNode = root.path("data");
            String type = dataNode.path("type").asText();
            JsonNode orderNode = dataNode.path("order");

            if (!"order".equals(type) || orderNode.isMissingNode()) {
                log.warn("[Ifdain] Webhook unknown type={} or missing order", type);
                return WebhookResult.fail("unknown type or missing order data");
            }

            // 2. 提取订单字段
            String outTradeNo = orderNode.path("out_trade_no").asText();
            String userId = orderNode.path("user_id").asText();
            String planId = orderNode.path("plan_id").asText();
            String totalAmount = orderNode.path("total_amount").asText();

            // 3. 签名验证 (可选)
            if (properties.isEnableWebhookSignature() && signHeader != null && !signHeader.isEmpty()) {
                String publicKey = properties.getWebhookPublicKey();
                if (publicKey != null && !publicKey.isEmpty()) {
                    boolean valid = AfdianSignatureUtil.verifyWebhookSignature(
                            outTradeNo, userId, planId, totalAmount, signHeader, publicKey
                    );
                    if (!valid) {
                        log.warn("[Ifdain] Webhook signature invalid for order={}", outTradeNo);
                        return WebhookResult.fail("invalid webhook signature");
                    }
                    log.info("[Ifdain] Webhook signature verified for order={}", outTradeNo);
                }
            }

            // 4. 幂等处理 - 已存在的订单跳过
            Optional<IfdianOrder> existing = orderRepository.findByOutTradeNo(outTradeNo);
            if (existing.isPresent()) {
                log.info("[Ifdain] Order already exists, skip. outTradeNo={}", outTradeNo);
                // 仍然返回成功，避免爱发电重试
                return WebhookResult.success("already exists", true);
            }

            // 5. 映射为实体
            IfdianOrder order = mapOrder(orderNode, rawBody);
            orderRepository.save(order);

            log.info("[Ifdain] Webhook order saved. outTradeNo={}, amount={}, planId={}",
                    outTradeNo, order.getTotalAmount(), planId);

            // 6. 调用业务处理器（自定义或默认）
            invokeOrderProcessor(order);

            // 7. 触发外部支付回调 (异步，不影响 Webhook 返回)
            try {
                callbackService.processPaymentCallback(order);
            } catch (Exception e) {
                log.error("[Ifdain] Callback processing threw unexpected error for order={}", outTradeNo, e);
            }

            return WebhookResult.success("saved", false);

        } catch (JsonProcessingException e) {
            log.error("[Ifdain] Failed to parse webhook body", e);
            return WebhookResult.fail("invalid json");
        } catch (Exception e) {
            log.error("[Ifdain] Unexpected error processing webhook", e);
            return WebhookResult.fail("internal error");
        }
    }

    /**
     * 将 JSON 订单节点映射为实体并保存（不触发业务处理器）
     *
     * <p>用于从 API 主动拉取订单同步到本地数据库，跳过签名验证和回调时间设置。
     * 已存在的订单（按 out_trade_no 判重）将被跳过。</p>
     *
     * @param orderNode 订单 JSON 节点 (来自 API 响应的 list 元素)
     * @param rawJson   原始 JSON 字符串快照
     * @return 保存的订单实体，若已存在则返回 null
     */
    @Transactional
    public IfdianOrder saveOrderFromApi(JsonNode orderNode, String rawJson) {
        String outTradeNo = orderNode.path("out_trade_no").asText();
        if (orderRepository.existsByOutTradeNo(outTradeNo)) {
            log.debug("[Ifdain] Sync skip, order already exists: {}", outTradeNo);
            return null;
        }
        IfdianOrder order = mapOrder(orderNode, rawJson != null ? rawJson : orderNode.toString());
        order.setReceivedAt(LocalDateTime.now());
        orderRepository.save(order);
        log.debug("[Ifdain] Sync saved order: {}", outTradeNo);
        return order;
    }

    /**
     * 将 JSON 订单节点映射为实体
     */
    private IfdianOrder mapOrder(JsonNode node, String rawBody) {
        IfdianOrder order = new IfdianOrder();

        order.setOutTradeNo(node.path("out_trade_no").asText());
        order.setCustomOrderId(nullIfEmpty(node.path("custom_order_id").asText()));
        order.setUserId(node.path("user_id").asText());
        order.setUserPrivateId(nullIfEmpty(node.path("user_private_id").asText()));
        order.setPlanId(node.path("plan_id").asText());

        int month = node.path("month").asInt(0);
        order.setSponsorMonth(month > 0 ? month : null);

        order.setTotalAmount(new BigDecimal(nullIfEmpty(node.path("total_amount").asText("0"))));
        order.setShowAmount(parseBigDecimal(node.path("show_amount").asText()));

        order.setStatus(OrderStatus.fromValue(node.path("status").asInt(2)));
        order.setRemark(nullIfEmpty(node.path("remark").asText()));

        order.setRedeemId(nullIfEmpty(node.path("redeem_id").asText()));
        order.setProductType(node.path("product_type").asInt(0));
        order.setDiscount(parseBigDecimal(node.path("discount").asText()));

        order.setSkuDetail(node.path("sku_detail").isArray()
                ? node.path("sku_detail").toString()
                : nullIfEmpty(node.path("sku_detail").asText()));

        order.setAddressPerson(nullIfEmpty(node.path("address_person").asText()));
        order.setAddressPhone(nullIfEmpty(node.path("address_phone").asText()));
        order.setAddressAddress(nullIfEmpty(node.path("address_address").asText()));

        // Webhook 接收时间 (爱发电未在 Webhook 中提供精确支付时间，此字段为收到回调的时间)
        order.setReceivedAt(LocalDateTime.now());

        // 保存原始数据快照
        order.setRawData(rawBody);

        return order;
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.trim().isEmpty() || "null".equals(value)) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String nullIfEmpty(String value) {
        return (value == null || value.trim().isEmpty()) ? null : value.trim();
    }

    /**
     * 调用配置的订单业务处理器
     *
     * <p>根据 {@code ifdain.order-processor-bean} 配置的 bean 名称，从 Spring 容器中
     * 获取 {@link IfdianOrderProcessor} 实例并执行 {@code process(order)}。</p>
     */
    private void invokeOrderProcessor(IfdianOrder order) {
        String beanName = properties.getOrderProcessorBean();
        try {
            IfdianOrderProcessor processor = applicationContext.getBean(beanName, IfdianOrderProcessor.class);
            processor.process(order);
            log.info("[Ifdain] Order processor '{}' executed for order={}", beanName, order.getOutTradeNo());
        } catch (Exception e) {
            log.error("[Ifdain] Failed to invoke order processor '{}' for order={}", beanName, order.getOutTradeNo(), e);
        }
    }

    // ===== 内部结果类 =====

    /**
     * Webhook 处理结果
     */
    @lombok.Value
    public static class WebhookResult {
        boolean success;
        String message;
        boolean duplicate;
        int ec;

        public static WebhookResult success(String message, boolean duplicate) {
            return new WebhookResult(true, message, duplicate, 200);
        }

        public static WebhookResult fail(String message) {
            return new WebhookResult(false, message, false, 400);
        }
    }
}
