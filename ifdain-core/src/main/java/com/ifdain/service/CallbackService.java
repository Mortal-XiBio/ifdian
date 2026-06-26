package com.ifdain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifdain.config.IfdainProperties;
import com.ifdain.entity.IfdianOrder;
import com.ifdain.entity.PaymentRequest;
import com.ifdain.entity.PaymentRequestStatus;
import com.ifdain.repository.IfdianOrderRepository;
import com.ifdain.repository.PaymentRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 外部支付回调服务
 *
 * <p>负责在 Webhook 匹配到外部支付请求后，向外部程序发送 HTTP 回调通知。
 * 采用异步执行 + 指数退避重试，确保回调失败不影响 Webhook 正常响应。</p>
 *
 * <h3>核心流程</h3>
 * <ol>
 *   <li>WebhookService 调用 {@link #processPaymentCallback(IfdianOrder)} 查找匹配的 PaymentRequest</li>
 *   <li>更新 PaymentRequest 状态为 PAID</li>
 *   <li>异步执行 {@link #doCallback(PaymentRequest, IfdianOrder)} 发送 HTTP POST</li>
 *   <li>回调成功 → 状态更新为 CALLBACK_SUCCESS；失败 → 保留 PAID，记录错误</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackService {

    private final PaymentRequestRepository paymentRequestRepository;
    private final IfdianOrderRepository orderRepository;
    private final IfdainProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // ===== 公开方法 =====

    /**
     * 处理 Webhook 支付回调
     *
     * <p>由 {@link IfdianWebhookService} 在保存订单后调用。
     * 按 customOrderId 查找匹配的 PaymentRequest，更新状态并触发异步 HTTP 回调。</p>
     *
     * @param order Webhook 推送的订单实体
     */
    public void processPaymentCallback(IfdianOrder order) {
        String customOrderId = order.getCustomOrderId();
        if (customOrderId == null || customOrderId.isEmpty()) {
            return;
        }

        Optional<PaymentRequest> opt = paymentRequestRepository.findByCustomOrderId(customOrderId);
        if (!opt.isPresent()) {
            log.debug("[Ifdain] No PaymentRequest found for customOrderId={}, skipping callback", customOrderId);
            return;
        }

        PaymentRequest pr = opt.get();
        if (pr.getStatus() != PaymentRequestStatus.PENDING) {
            log.info("[Ifdain] PaymentRequest already processed: requestId={}, customOrderId={}, status={}",
                    pr.getRequestId(), customOrderId, pr.getStatus());
            return;
        }

        // 更新为已支付状态
        pr.setStatus(PaymentRequestStatus.PAID);
        pr.setOutTradeNo(order.getOutTradeNo());
        paymentRequestRepository.save(pr);

        log.info("[Ifdain] PaymentRequest matched: requestId={}, customOrderId={}, outTradeNo={}",
                pr.getRequestId(), customOrderId, order.getOutTradeNo());

        // 异步发送回调
        doCallback(pr, order);
    }

    /**
     * 手动重试回调
     *
     * <p>由 ExternalApiController 的重试端点调用。仅当 PaymentRequest 状态为 PAID 时有效。</p>
     *
     * @param pr 支付请求实体
     * @return true 表示回调成功，false 表示仍然失败
     */
    public boolean retryCallback(PaymentRequest pr) {
        if (pr.getStatus() != PaymentRequestStatus.PAID) {
            log.warn("[Ifdain] Cannot retry callback for non-PAID request: requestId={}, status={}",
                    pr.getRequestId(), pr.getStatus());
            return false;
        }
        // 重置重试计数，给予新的重试机会
        pr.setCallbackRetryCount(0);

        // 查找关联的实际订单，用于回调中传递真实月数和金额
        IfdianOrder order = null;
        if (pr.getOutTradeNo() != null) {
            order = orderRepository.findByOutTradeNo(pr.getOutTradeNo()).orElse(null);
        }
        return executeCallback(pr, order);
    }

    /**
     * 生成回调签名密钥
     *
     * <p>使用 SecureRandom 生成 32 字节随机密钥，以 Base64 编码返回。</p>
     *
     * @return Base64 编码的 44 字符密钥字符串
     */
    public String generateSecret() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * 对请求体计算 HMAC-SHA256 签名
     *
     * @param body   原始 JSON body 字符串
     * @param secret Base64 编码的密钥
     * @return Base64 编码的签名结果
     */
    public String signBody(String body, String secret) {
        try {
            byte[] secretBytes = Base64.getDecoder().decode(secret);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            byte[] signature = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            log.error("[Ifdain] Failed to compute HMAC-SHA256 signature", e);
            throw new RuntimeException("HMAC-SHA256 signing failed", e);
        }
    }

    // ===== 异步回调 =====

    /**
     * 异步执行 HTTP 回调
     *
     * <p>构建 JSON body → 签名 → POST → 重试。此方法在独立线程中执行，
     * 不会阻塞 Webhook 响应。</p>
     */
    @Async
    public void doCallback(PaymentRequest pr, IfdianOrder order) {
        // 重置重试计数（首次回调）
        pr.setCallbackRetryCount(0);
        boolean success = executeCallback(pr, order);
        if (!success) {
            log.warn("[Ifdain] Callback failed after all retries: requestId={}, callbackUrl={}",
                    pr.getRequestId(), pr.getCallbackUrl());
        }
    }

    /**
     * 执行回调（含重试逻辑）
     *
     * <p>指数退避: 1s → 2s → 4s → 8s，最多尝试 maxRetries + 1 次。</p>
     *
     * @param pr    支付请求实体
     * @param order 实际订单（可能为 null，如重试时找不到）
     * @return true 表示回调成功
     */
    private boolean executeCallback(PaymentRequest pr, IfdianOrder order) {
        int maxRetries = properties.getCallbackMaxRetries();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // 指数退避（首次不等待）
                if (attempt > 0) {
                    long delayMs = (long) Math.pow(2, attempt - 1) * 1000L;
                    log.info("[Ifdain] Callback retry {}/{} after {}ms: requestId={}",
                            attempt, maxRetries, delayMs, pr.getRequestId());
                    Thread.sleep(delayMs);
                }

                // 构建回调 JSON body（包含实际订单信息）
                String body = buildCallbackBody(pr, order);
                String signature = signBody(body, pr.getSignatureSecret());

                // 发送 HTTP POST
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("X-Ifdain-Signature", signature);
                headers.set("X-Ifdain-Request-Id", pr.getRequestId());

                HttpEntity<String> entity = new HttpEntity<>(body, headers);

                int timeout = properties.getCallbackTimeoutMs();
                log.info("[Ifdain] Sending callback to {}: requestId={}", pr.getCallbackUrl(), pr.getRequestId());

                ResponseEntity<String> response = restTemplate.postForEntity(
                        pr.getCallbackUrl(), entity, String.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    pr.setStatus(PaymentRequestStatus.CALLBACK_SUCCESS);
                    pr.setCallbackLastAttempt(LocalDateTime.now());
                    pr.setCallbackError(null);
                    paymentRequestRepository.save(pr);
                    log.info("[Ifdain] Callback success: requestId={}, status={}",
                            pr.getRequestId(), response.getStatusCodeValue());
                    return true;
                } else {
                    recordCallbackFailure(pr, "HTTP " + response.getStatusCodeValue() + ": " + response.getBody());
                    log.warn("[Ifdain] Callback non-2xx: requestId={}, status={}",
                            pr.getRequestId(), response.getStatusCodeValue());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordCallbackFailure(pr, "Callback interrupted");
                return false;
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (errorMsg == null || errorMsg.length() > 500) {
                    errorMsg = e.getClass().getSimpleName() + ": " +
                            (errorMsg != null ? errorMsg.substring(0, 500) : "unknown");
                }
                recordCallbackFailure(pr, errorMsg);
                log.warn("[Ifdain] Callback attempt {}/{} failed: requestId={}, error={}",
                        attempt, maxRetries, pr.getRequestId(), errorMsg);
            }
        }

        return false;
    }

    /**
     * 记录回调失败
     */
    private void recordCallbackFailure(PaymentRequest pr, String errorMsg) {
        pr.setCallbackRetryCount(pr.getCallbackRetryCount() + 1);
        pr.setCallbackLastAttempt(LocalDateTime.now());
        pr.setCallbackError(errorMsg);
        paymentRequestRepository.save(pr);
    }

    /**
     * 构建回调 JSON body
     *
     * <p>同时包含 PaymentRequest 中的请求信息和 IfdianOrder 中的实际订单数据，
     * 让调用方能获取用户实际购买的月数和金额（可能与请求时的预期不同）。</p>
     */
    private String buildCallbackBody(PaymentRequest pr, IfdianOrder order) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", pr.getRequestId());
        body.put("customOrderId", pr.getCustomOrderId());
        body.put("status", "paid");
        body.put("outTradeNo", pr.getOutTradeNo());
        body.put("planId", pr.getPlanId());
        body.put("userId", pr.getUserId());

        // 优先使用实际订单的金额，回退到请求时的预期金额
        if (order != null && order.getTotalAmount() != null) {
            body.put("totalAmount", String.format("%.2f", order.getTotalAmount()));
        } else if (pr.getTotalAmount() != null) {
            body.put("totalAmount", String.format("%.2f", pr.getTotalAmount()));
        } else {
            body.put("totalAmount", null);
        }

        // 实际订单的额外信息（用户在爱发电页面可能修改了月数等）
        if (order != null) {
            body.put("actualMonth", order.getSponsorMonth());
            body.put("showAmount", order.getShowAmount() != null ? String.format("%.2f", order.getShowAmount()) : null);
            body.put("discount", order.getDiscount() != null ? String.format("%.2f", order.getDiscount()) : null);
            body.put("productType", order.getProductType());
            body.put("userPrivateId", order.getUserPrivateId());
        }

        body.put("receivedAt", pr.getUpdatedAt() != null
                ? pr.getUpdatedAt().format(ISO_FORMAT)
                : LocalDateTime.now().format(ISO_FORMAT));

        return objectMapper.writeValueAsString(body);
    }
}
