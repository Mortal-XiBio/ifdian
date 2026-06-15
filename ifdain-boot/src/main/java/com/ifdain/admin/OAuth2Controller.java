package com.ifdain.admin;

import com.ifdain.entity.OAuth2Binding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * OAuth2 授权控制器
 *
 * <p>提供爱发电 OAuth2 授权的 Web 端点，供其他项目或自身前端调用。</p>
 *
 * <h3>使用流程</h3>
 * <ol>
 *   <li>前端调用 {@code GET /api/external/oauth2/authorize} 获取授权 URL</li>
 *   <li>引导用户跳转到爱发电授权页面</li>
 *   <li>用户同意后，爱发电回调到 redirect_uri</li>
 *   <li>后端调用 {@code GET /api/external/oauth2/callback?code=xxx&state=xxx} 完成授权</li>
 *   <li>获取到的 user_private_id 可用于后续订阅验证</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/external/oauth2")
@RequiredArgsConstructor
public class OAuth2Controller {

    private final OAuth2Service oauth2Service;
    private final SystemConfigService configService;

    /**
     * 获取 OAuth2 授权 URL
     *
     * @param redirectUri 授权成功后的回调地址
     * @param state       防 CSRF 的 state 参数（可选）
     * @return 授权 URL，前端应 302 跳转到此地址
     */
    @GetMapping("/authorize")
    public ResponseEntity<Map<String, Object>> authorize(
            @RequestParam String redirectUri,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String apiKey,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKeyHeader) {

        if (!authenticate(apiKey, apiKeyHeader)) {
            return error(401, "invalid api key");
        }

        if (state == null || state.isEmpty()) {
            state = UUID.randomUUID().toString().replace("-", "");
        }

        String authUrl = oauth2Service.buildAuthorizationUrl(redirectUri, state);

        Map<String, Object> data = new HashMap<>();
        data.put("authorization_url", authUrl);
        data.put("state", state);
        return success(data);
    }

    /**
     * OAuth2 回调处理
     *
     * <p>爱发电会将用户重定向到此地址，携带 code 和 state 参数。
     * 此端点完成 code 换 token 的流程并保存用户绑定。</p>
     *
     * @param code        爱发电返回的授权码
     * @param state       防 CSRF 参数（当前不做额外验证，由调用方自行校验）
     * @param redirectUri 与发起授权时相同的回调地址
     * @param localUserId 可选：要绑定的本地系统用户ID
     * @return 授权用户信息
     */
    @GetMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback(
            @RequestParam String code,
            @RequestParam(required = false) String state,
            @RequestParam String redirectUri,
            @RequestParam(required = false) String localUserId,
            @RequestParam(required = false) String apiKey,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKeyHeader) {

        if (!authenticate(apiKey, apiKeyHeader)) {
            return error(401, "invalid api key");
        }

        OAuth2Service.OAuth2UserInfo userInfo = oauth2Service.handleCallback(code, redirectUri, localUserId);
        if (userInfo == null) {
            return error(502, "failed to exchange authorization code");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("user_id", userInfo.getUserId());
        data.put("user_private_id", userInfo.getUserPrivateId());
        data.put("name", userInfo.getName());
        data.put("avatar", userInfo.getAvatar());
        data.put("binding_id", userInfo.getBindingId());
        if (state != null) {
            data.put("state", state);
        }
        return success(data);
    }

    /**
     * 查询 OAuth2 绑定信息
     *
     * @param userPrivateId 爱发电用户私有标识（二选一）
     * @param localUserId   本地系统用户ID（二选一）
     * @return 绑定信息
     */
    @GetMapping("/binding")
    public ResponseEntity<Map<String, Object>> getBinding(
            @RequestParam(required = false) String userPrivateId,
            @RequestParam(required = false) String localUserId,
            @RequestParam(required = false) String apiKey,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKeyHeader) {

        if (!authenticate(apiKey, apiKeyHeader)) {
            return error(401, "invalid api key");
        }

        Optional<OAuth2Binding> bindingOpt;
        if (userPrivateId != null && !userPrivateId.isEmpty()) {
            bindingOpt = oauth2Service.findBinding(userPrivateId);
        } else if (localUserId != null && !localUserId.isEmpty()) {
            bindingOpt = oauth2Service.findBindingByLocalUser(localUserId);
        } else {
            return error(400, "userPrivateId or localUserId is required");
        }

        if (bindingOpt.isEmpty()) {
            return success(Collections.singletonMap("found", false));
        }

        OAuth2Binding binding = bindingOpt.get();
        Map<String, Object> data = new HashMap<>();
        data.put("found", true);
        data.put("user_id", binding.getUserId());
        data.put("user_private_id", binding.getUserPrivateId());
        data.put("name", binding.getName());
        data.put("avatar", binding.getAvatar());
        data.put("local_user_id", binding.getLocalUserId());
        data.put("created_at", binding.getCreatedAt() != null ? binding.getCreatedAt().toString() : null);
        return success(data);
    }

    // ===== 辅助方法 =====

    private boolean authenticate(String apiKeyParam, String apiKeyHeader) {
        String providedKey = apiKeyHeader != null ? apiKeyHeader : apiKeyParam;
        if (providedKey == null || providedKey.isEmpty()) {
            return false;
        }
        String expectedKey = configService.getOrDefault("ifdain.external_api_key", "");
        if (expectedKey.isEmpty()) {
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
}
