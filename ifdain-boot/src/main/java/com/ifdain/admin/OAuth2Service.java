package com.ifdain.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifdain.entity.OAuth2Binding;
import com.ifdain.repository.OAuth2BindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * OAuth2 授权服务
 *
 * <p>提供爱发电 OAuth2 authorization_code 模式的完整流程：
 * <ol>
 *   <li>生成授权 URL 引导用户跳转</li>
 *   <li>用回调 code 换取 access_token 及用户信息</li>
 *   <li>将用户绑定信息存储到本地数据库</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuth2Service {

    private final SystemConfigService configService;
    private final OAuth2BindingRepository bindingRepository;
    private final ObjectMapper objectMapper;

    private static final String AUTHORIZE_URL = "https://ifdian.net/oauth2/authorize";
    private static final String TOKEN_URL = "https://ifdian.net/api/oauth2/access_token";

    /**
     * 生成 OAuth2 授权 URL
     *
     * @param redirectUri 回调地址
     * @param state       状态参数（防 CSRF）
     * @return 授权 URL 字符串
     */
    public String buildAuthorizationUrl(String redirectUri, String state) {
        String clientId = getClientId();

        StringBuilder url = new StringBuilder(AUTHORIZE_URL);
        url.append("?response_type=code");
        url.append("&scope=basic");
        url.append("&client_id=").append(urlEncode(clientId));
        url.append("&redirect_uri=").append(urlEncode(redirectUri));
        if (state != null && !state.isEmpty()) {
            url.append("&state=").append(urlEncode(state));
        }

        return url.toString();
    }

    /**
     * 用授权码换取用户信息
     *
     * @param code        授权码
     * @param redirectUri 回调地址（必须与发起授权时一致）
     * @return OAuth2 用户信息，失败返回 null
     */
    public OAuth2UserInfo exchangeCode(String code, String redirectUri) {
        String clientId = getClientId();
        String clientSecret = getClientSecret();

        HttpURLConnection conn = null;
        try {
            // 构造 form 参数
            String formData = "grant_type=authorization_code"
                    + "&client_id=" + urlEncode(clientId)
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&code=" + urlEncode(code)
                    + "&redirect_uri=" + urlEncode(redirectUri);

            URL url = URI.create(TOKEN_URL).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(formData.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.error("[Ifdain] OAuth2 token exchange failed, httpCode={}", responseCode);
                return null;
            }

            byte[] responseBytes = conn.getInputStream().readAllBytes();
            String responseBody = new String(responseBytes, StandardCharsets.UTF_8);

            JsonNode result = objectMapper.readTree(responseBody);
            int ec = result.path("ec").asInt();

            if (ec != 200) {
                log.warn("[Ifdain] OAuth2 token exchange returned ec={}, response={}", ec, responseBody);
                return null;
            }

            JsonNode data = result.path("data");
            OAuth2UserInfo info = new OAuth2UserInfo();
            info.setUserId(data.path("user_id").asText());
            info.setUserPrivateId(data.path("user_private_id").asText());
            info.setName(data.path("name").asText());
            info.setAvatar(data.path("avatar").asText());

            log.info("[Ifdain] OAuth2 token exchanged successfully for user={}", info.getUserId());
            return info;

        } catch (Exception e) {
            log.error("[Ifdain] OAuth2 token exchange error", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 执行完整的 OAuth2 回调流程：换 token 并保存绑定
     *
     * @param code        授权码
     * @param redirectUri 回调地址
     * @param localUserId 可选：业务系统用户标识
     * @return 用户信息，失败返回 null
     */
    @Transactional
    public OAuth2UserInfo handleCallback(String code, String redirectUri, String localUserId) {
        OAuth2UserInfo info = exchangeCode(code, redirectUri);
        if (info == null) {
            return null;
        }

        // 保存或更新绑定
        Optional<OAuth2Binding> existing = bindingRepository.findByUserPrivateId(info.getUserPrivateId());
        OAuth2Binding binding;
        if (existing.isPresent()) {
            binding = existing.get();
            binding.setUserId(info.getUserId());
            binding.setName(info.getName());
            binding.setAvatar(info.getAvatar());
            if (localUserId != null) {
                binding.setLocalUserId(localUserId);
            }
        } else {
            binding = OAuth2Binding.builder()
                    .userId(info.getUserId())
                    .userPrivateId(info.getUserPrivateId())
                    .name(info.getName())
                    .avatar(info.getAvatar())
                    .localUserId(localUserId)
                    .clientId(getClientId())
                    .build();
        }

        bindingRepository.save(binding);

        // 将 userPrivateId 返回给调用方
        info.setBindingId(binding.getId());
        return info;
    }

    /**
     * 查询用户的 OAuth2 绑定信息
     *
     * @param userPrivateId 用户私有标识
     * @return 绑定信息
     */
    public Optional<OAuth2Binding> findBinding(String userPrivateId) {
        return bindingRepository.findByUserPrivateId(userPrivateId);
    }

    /**
     * 查询本地用户绑定的爱发电信息
     *
     * @param localUserId 本地系统用户ID
     * @return 绑定信息
     */
    public Optional<OAuth2Binding> findBindingByLocalUser(String localUserId) {
        return bindingRepository.findByLocalUserId(localUserId);
    }

    // ===== 内部 =====

    private String getClientId() {
        return configService.getOrDefault("ifdain.oauth2_client_id", "");
    }

    private String getClientSecret() {
        return configService.getOrDefault("ifdain.oauth2_client_secret", "");
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    // ===== OAuth2 用户信息 =====

    @lombok.Data
    public static class OAuth2UserInfo {
        private String userId;
        private String userPrivateId;
        private String name;
        private String avatar;
        private Long bindingId;
    }
}
