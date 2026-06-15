package com.ifdain.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

/**
 * 爱发电签名工具类
 *
 * <h3>API 签名</h3>
 * {@code sign = md5(token + params{params}ts{ts}user_id{user_id})}
 *
 * <h3>Webhook 签名</h3>
 * RSA-SHA256 验证: 拼接 {@code out_trade_no + user_id + plan_id + total_amount}
 */
@Slf4j
public class AfdianSignatureUtil {

    private static final String MD5 = "MD5";
    private static final String RSA = "RSA";
    private static final String SHA256_WITH_RSA = "SHA256withRSA";

    private AfdianSignatureUtil() {}

    // ==================== API 签名 ====================

    /**
     * 生成 API 请求签名 (MD5)
     *
     * @param token  API Token
     * @param params params 参数字符串 (JSON)
     * @param ts     秒级时间戳
     * @param userId 开发者 user_id
     * @return 32 位小写 MD5 签名
     */
    public static String signApiRequest(String token, String params, long ts, String userId) {
        String raw = token + "params" + (params != null ? params : "") + "ts" + ts + "user_id" + userId;
        return md5Hex(raw);
    }

    /**
     * 验证 API 响应签名
     *
     * @param token       API Token
     * @param params      params 参数字符串
     * @param ts          签名时使用的时间戳
     * @param userId      开发者 user_id
     * @param signature   待验证的签名
     * @return true 如果签名匹配
     */
    public static boolean verifyApiSignature(String token, String params, long ts,
                                              String userId, String signature) {
        String expected = signApiRequest(token, params, ts, userId);
        return expected.equalsIgnoreCase(signature);
    }

    // ==================== Webhook 签名验证 ====================

    /**
     * 验证 Webhook RSA 签名
     *
     * <p>签名字符串格式: {@code out_trade_no + user_id + plan_id + total_amount}</p>
     *
     * <p><b>注意:</b> {@code totalAmount} 必须使用 {@code JsonNode.asText()} 提取的原始字符串
     * （如 {@code "5.00"}），而非 BigDecimal 格式化后的值。
     * 签名验证成功与否取决于该字符串与爱发电签名时使用的值严格一致。</p>
     *
     * @param outTradeNo  订单号
     * @param userId      用户ID
     * @param planId      方案ID
     * @param totalAmount 实付金额 (字符串形式，必须与爱发电签名时使用的格式一致)
     * @param sign        Base64 编码的签名
     * @param publicKeyPem RSA 公钥 (PEM 格式)
     * @return true 如果签名验证通过
     */
    public static boolean verifyWebhookSignature(String outTradeNo, String userId, String planId,
                                                  String totalAmount, String sign, String publicKeyPem) {
        try {
            String signStr = outTradeNo + userId + planId + totalAmount;
            PublicKey publicKey = loadPublicKey(publicKeyPem);

            Signature signature = Signature.getInstance(SHA256_WITH_RSA);
            signature.initVerify(publicKey);
            signature.update(signStr.getBytes(StandardCharsets.UTF_8));

            byte[] signBytes = Base64.getDecoder().decode(sign);
            return signature.verify(signBytes);
        } catch (Exception e) {
            log.error("[Ifdain] Webhook signature verification failed", e);
            return false;
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 计算 MD5 十六进制小写字符串
     */
    public static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(MD5);
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    /**
     * 从 PEM 字符串加载 RSA 公钥
     */
    public static PublicKey loadPublicKey(String publicKeyPem) throws GeneralSecurityException {
        String keyContent = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(keyContent);
        java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(RSA);
        return keyFactory.generatePublic(spec);
    }

    /**
     * 判断当前环境是否支持 RSA-SHA256 (JDK 8+ 原生支持)
     */
    public static boolean isRsaSupported() {
        try {
            Signature.getInstance(SHA256_WITH_RSA);
            return true;
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }
}
