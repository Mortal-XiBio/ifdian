package com.ifdain.util;

import com.ifdain.IfdainTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AfdianSignatureUtil} 的单元测试
 *
 * 覆盖范围：
 * - API 签名（MD5）的生成与验证
 * - Webhook 回调签名（RSA-SHA256）的验证
 * - 空值 / 异常边界情况
 */
@DisplayName("AfdianSignatureUtil 工具类测试")
class AfdianSignatureUtilTest extends IfdainTestSupport {

    // ------------------------------------------------------------------
    // API 签名测试（基于 MD5）
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("API 签名 (MD5)")
    class ApiSignatureTest {

        private final String token = TEST_TOKEN;
        private final String userId = TEST_USER_ID;

        @Test
        @DisplayName("生成 API 签名 — 基本场景")
        void testSign() {
            long ts = System.currentTimeMillis() / 1000;
            String sign = AfdianSignatureUtil.signApiRequest(token, "{}", ts, userId);
            assertNotNull(sign);
            assertEquals(32, sign.length()); // MD5 hex = 32 chars
        }

        @Test
        @DisplayName("生成 API 签名 — 结果可重复（确定性）")
        void testSignDeterministic() {
            long ts = 1700000000L;
            String sign1 = AfdianSignatureUtil.signApiRequest(token, "{}", ts, userId);
            String sign2 = AfdianSignatureUtil.signApiRequest(token, "{}", ts, userId);
            assertEquals(sign1, sign2);
        }

        @Test
        @DisplayName("生成 API 签名 — 不同参数产生不同签名")
        void testSignDifferentParams() {
            long ts = 1700000000L;
            String s1 = AfdianSignatureUtil.signApiRequest(token, "{\"a\":1}", ts, userId);
            String s2 = AfdianSignatureUtil.signApiRequest(token, "{\"a\":2}", ts, userId);
            assertNotEquals(s1, s2);
        }

        @Test
        @DisplayName("生成 API 签名 — 不同 token 产生不同签名")
        void testSignDifferentToken() {
            long ts = 1700000000L;
            String s1 = AfdianSignatureUtil.signApiRequest("token_a", "{}", ts, userId);
            String s2 = AfdianSignatureUtil.signApiRequest("token_b", "{}", ts, userId);
            assertNotEquals(s1, s2);
        }

        @Test
        @DisplayName("生成 API 签名 — 空参数字符串也能正常工作")
        void testSignEmptyParams() {
            long ts = 1700000000L;
            String sign = AfdianSignatureUtil.signApiRequest(token, "", ts, userId);
            assertNotNull(sign);
            assertEquals(32, sign.length());
        }

        @Test
        @DisplayName("生成 API 签名 — 复杂嵌套参数")
        void testSignNestedParams() {
            long ts = 1700000000L;
            String params = "{\"list\":[\"a\",\"b\",\"c\"],\"nested\":{\"key\":\"value\"}}";
            String sign = AfdianSignatureUtil.signApiRequest(token, params, ts, userId);
            assertNotNull(sign);
            assertEquals(32, sign.length());
        }

        @Test
        @DisplayName("验证 API 签名 — 有效签名")
        void testVerifyValid() {
            long ts = System.currentTimeMillis() / 1000;
            String sign = AfdianSignatureUtil.signApiRequest(token, "{}", ts, userId);
            assertTrue(AfdianSignatureUtil.verifyApiSignature(token, "{}", ts, userId, sign));
        }

        @Test
        @DisplayName("验证 API 签名 — 无效签名返回 false")
        void testVerifyInvalid() {
            long ts = System.currentTimeMillis() / 1000;
            assertFalse(AfdianSignatureUtil.verifyApiSignature(token, "{}", ts, userId, "wrong_sign"));
        }

        @Test
        @DisplayName("验证 API 签名 — 空签名返回 false")
        void testVerifyEmptySign() {
            long ts = System.currentTimeMillis() / 1000;
            assertFalse(AfdianSignatureUtil.verifyApiSignature(token, "{}", ts, userId, ""));
            assertFalse(AfdianSignatureUtil.verifyApiSignature(token, "{}", ts, userId, null));
        }

        @Test
        @DisplayName("验证 API 签名 — 不同 token 返回 false")
        void testVerifyDifferentToken() {
            long ts = 1700000000L;
            String sign = AfdianSignatureUtil.signApiRequest("token_a", "{}", ts, userId);
            assertFalse(AfdianSignatureUtil.verifyApiSignature("token_b", "{}", ts, userId, sign));
        }
    }

    // ------------------------------------------------------------------
    // Webhook 回调签名测试（基于 RSA-SHA256）
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Webhook 回调签名 (RSA-SHA256)")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class WebhookSignatureTest {

        private PublicKey publicKey;
        private PrivateKey privateKey;

        @BeforeAll
        void setup() throws Exception {
            byte[] pubEncoded = Base64.getMimeDecoder().decode(
                stripPem(TEST_WEBHOOK_PUBLIC_KEY));
            byte[] privEncoded = Base64.getMimeDecoder().decode(
                stripPem(TEST_WEBHOOK_PRIVATE_KEY));

            KeyFactory kf = KeyFactory.getInstance("RSA");
            publicKey = kf.generatePublic(new X509EncodedKeySpec(pubEncoded));
            privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privEncoded));
        }

        @Test
        @DisplayName("验证 Webhook 签名 — 有效签名应通过")
        void testVerifyWebhookValid() throws Exception {
            String outTradeNo = "order_123";
            String userId = "user_abc";
            String planId = "plan_456";
            String totalAmount = "5.00";
            String signStr = outTradeNo + userId + planId + totalAmount;
            String signature = signRsa(signStr, privateKey);

            boolean result = AfdianSignatureUtil.verifyWebhookSignature(
                outTradeNo, userId, planId, totalAmount, signature, TEST_WEBHOOK_PUBLIC_KEY);
            assertTrue(result, "有效签名应验证通过");
        }

        @Test
        @DisplayName("验证 Webhook 签名 — 数据被篡改不应通过")
        void testVerifyWebhookTamperedData() throws Exception {
            String outTradeNo = "order_123";
            String userId = "user_abc";
            String planId = "plan_456";
            String totalAmount = "5.00";
            String signStr = outTradeNo + userId + planId + totalAmount;
            String signature = signRsa(signStr, privateKey);

            boolean result = AfdianSignatureUtil.verifyWebhookSignature(
                "order_999", userId, planId, totalAmount, signature, TEST_WEBHOOK_PUBLIC_KEY);
            assertFalse(result, "篡改后的数据不应通过验证");
        }

        @Test
        @DisplayName("验证 Webhook 签名 — 无效签名不应通过")
        void testVerifyWebhookInvalidSignature() {
            boolean result = AfdianSignatureUtil.verifyWebhookSignature(
                "order_123", "user_abc", "plan_456", "5.00",
                "aW52YWxpZF9zaWc=", TEST_WEBHOOK_PUBLIC_KEY);
            assertFalse(result, "无效签名不应通过验证");
        }

        @Test
        @DisplayName("验证 Webhook 签名 — 空订单号返回 false")
        void testVerifyWebhookEmptyOutTradeNo() throws Exception {
            String signStr = "order_123" + "user_abc" + "plan_456" + "5.00";
            String signature = signRsa(signStr, privateKey);
            assertFalse(AfdianSignatureUtil.verifyWebhookSignature(
                "", "user_abc", "plan_456", "5.00", signature, TEST_WEBHOOK_PUBLIC_KEY));
            assertFalse(AfdianSignatureUtil.verifyWebhookSignature(
                null, "user_abc", "plan_456", "5.00", signature, TEST_WEBHOOK_PUBLIC_KEY));
        }

        @Test
        @DisplayName("验证 Webhook 签名 — 空签名返回 false")
        void testVerifyWebhookEmptySignature() {
            assertFalse(AfdianSignatureUtil.verifyWebhookSignature(
                "order_123", "user_abc", "plan_456", "5.00", "", TEST_WEBHOOK_PUBLIC_KEY));
            assertFalse(AfdianSignatureUtil.verifyWebhookSignature(
                "order_123", "user_abc", "plan_456", "5.00", null, TEST_WEBHOOK_PUBLIC_KEY));
        }

        @Test
        @DisplayName("验证 Webhook 签名 — 无效公钥返回 false（不抛异常）")
        void testVerifyWebhookInvalidPublicKey() {
            boolean result = AfdianSignatureUtil.verifyWebhookSignature(
                "order_123", "user_abc", "plan_456", "5.00", "c2ln", "invalid-key");
            assertFalse(result, "无效公钥应返回 false 且不抛异常");
        }

        @Test
        @DisplayName("验证 Webhook 签名 — 空公钥返回 false")
        void testVerifyWebhookEmptyPublicKey() {
            boolean result = AfdianSignatureUtil.verifyWebhookSignature(
                "order_123", "user_abc", "plan_456", "5.00", "c2ln", null);
            assertFalse(result, "空公钥应返回 false");
        }

        /** 去除 PEM 文件的头尾标记和空白 */
        private String stripPem(String pem) {
            return pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        }

        /** 用 RSA 私钥对内容签名（Base64 输出） */
        private String signRsa(String data, PrivateKey key) throws Exception {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(data.getBytes(StandardCharsets.UTF_8));
            byte[] signed = sig.sign();
            return Base64.getEncoder().encodeToString(signed);
        }
    }
}
