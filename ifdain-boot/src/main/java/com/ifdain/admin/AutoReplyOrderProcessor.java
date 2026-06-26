package com.ifdain.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.ifdain.entity.IfdianOrder;
import com.ifdain.service.AfdianApiClient;
import com.ifdain.service.IfdianOrderProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 自动回复订单处理器
 *
 * <p>收到订单后自动向用户发送私信回复。回复内容优先级：</p>
 * <ol>
 *   <li>方案专属预设: {@code ifdain.auto_reply.plan.{planId}}</li>
 *   <li>全局默认消息: {@code ifdain.auto_reply_default}</li>
 *   <li>内置兜底: "感谢支持"</li>
 * </ol>
 *
 * <p>替代 {@code DefaultOrderProcessor}，通过 {@code ifdain.order-processor-bean} 配置激活。</p>
 */
@Slf4j
@Component("autoReplyOrderProcessor")
@RequiredArgsConstructor
public class AutoReplyOrderProcessor implements IfdianOrderProcessor {

    private static final String FALLBACK_MESSAGE = "感谢支持";

    /** 方案专属回复配置键前缀 */
    static final String PLAN_REPLY_PREFIX = "ifdain.auto_reply.plan.";

    private final SystemConfigService configService;
    private final AfdianApiClient apiClient;

    @Override
    public void process(IfdianOrder order) {
        // 1. 标记订单已处理
        order.setProcessed(1);

        // 2. 检查是否启用自动回复
        if (!"true".equals(configService.getOrDefault(SystemConfigService.KEY_AUTO_REPLY_ENABLED, "true"))) {
            log.info("[Ifdain] Auto-reply disabled, skip order={}", order.getOutTradeNo());
            return;
        }

        // 3. 获取用户 ID (优先 userPrivateId，回退 userId)
        String recipient = order.getUserPrivateId();
        if (recipient == null || recipient.isBlank()) {
            recipient = order.getUserId();
        }
        if (recipient == null || recipient.isBlank()) {
            log.warn("[Ifdain] Auto-reply skip: no user ID for order={}", order.getOutTradeNo());
            return;
        }

        // 4. 确定回复内容
        String message = resolveMessage(order.getPlanId());

        // 5. 发送私信
        try {
            JsonNode result = apiClient.sendPrivateMessage(recipient, message);
            if (result == null) {
                log.warn("[Ifdain] Auto-reply failed for order={}: sendPrivateMessage returned null",
                        order.getOutTradeNo());
                return;
            }
            int ec = result.path("ec").asInt(-1);
            if (ec == 200) {
                log.info("[Ifdain] Auto-reply sent to user={} for order={}, message='{}'",
                        recipient, order.getOutTradeNo(), message);
            } else {
                log.warn("[Ifdain] Auto-reply failed for order={}: ec={}, em={}",
                        order.getOutTradeNo(), ec, result.path("em").asText());
            }
        } catch (Exception e) {
            log.error("[Ifdain] Auto-reply error for order={}", order.getOutTradeNo(), e);
        }
    }

    /**
     * 根据方案 ID 确定回复内容
     */
    private String resolveMessage(String planId) {
        // 优先: 方案专属预设
        if (planId != null && !planId.isBlank()) {
            String planMsg = configService.getOrDefault(PLAN_REPLY_PREFIX + planId, "");
            if (!planMsg.isBlank()) {
                return planMsg;
            }
        }
        // 其次: 全局默认
        String defaultMsg = configService.getOrDefault(SystemConfigService.KEY_AUTO_REPLY_DEFAULT, "");
        if (!defaultMsg.isBlank()) {
            return defaultMsg;
        }
        // 兜底
        return FALLBACK_MESSAGE;
    }
}
