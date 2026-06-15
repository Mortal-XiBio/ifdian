package com.ifdain.controller;

import com.ifdain.service.IfdianWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 爱发电 Webhook 接收端点
 *
 * <p>默认路径: {@code POST /webhook/ifdian}</p>
 *
 * <p>爱发电要求必须返回 {@code {"ec":200,"em":""}} 否则会重试。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WebhookController {

    private final IfdianWebhookService webhookService;

    /**
     * 接收爱发电 Webhook 推送
     *
     * @param request 原始 HTTP 请求
     * @return 爱发电要求的固定响应格式
     */
    @PostMapping("${ifdain.webhook-path:/webhook/ifdian}")
    public ResponseEntity<Map<String, Object>> receiveWebhook(HttpServletRequest request) {
        String rawBody = null;
        String signHeader = request.getHeader("X-Signature");

        try {
            rawBody = new BufferedReader(request.getReader())
                    .lines()
                    .collect(Collectors.joining(System.lineSeparator()));

            if (rawBody == null || rawBody.trim().isEmpty()) {
                log.warn("[Ifdain] Webhook received empty body");
                return ResponseEntity.ok(Map.of("ec", 400, "em", "empty body"));
            }

            log.debug("[Ifdain] Webhook received: body={}", rawBody.length() > 500
                    ? rawBody.substring(0, 500) + "..." : rawBody);

            IfdianWebhookService.WebhookResult result = webhookService.handleWebhook(rawBody, signHeader);

            if (result.isSuccess()) {
                return ResponseEntity.ok(Map.of("ec", 200, "em", ""));
            } else {
                // 返回非 ec=200 会让爱发电重试
                log.warn("[Ifdain] Webhook handling failed: {}", result.getMessage());
                return ResponseEntity.ok(Map.of("ec", result.getEc(), "em", result.getMessage()));
            }

        } catch (Exception e) {
            // 捕获所有异常以确保始终向爱发电返回 200 (否则爱发电会无限重试)
            log.error("[Ifdain] Webhook processing error", e);
            return ResponseEntity.ok(Map.of("ec", 500, "em", "internal error"));
        }
    }

    /**
     * 健康检查端点
     */
    @GetMapping("/webhook/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "ifdain-webhook"
        ));
    }
}
