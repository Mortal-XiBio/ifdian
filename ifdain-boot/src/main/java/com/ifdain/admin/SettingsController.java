package com.ifdain.admin;

import com.ifdain.entity.SystemConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 系统设置管理控制器
 *
 * <p>提供爱发电 API 配置、Webhook 配置、Redis 配置等运行时设置的管理页面。</p>
 */
@Controller
@RequestMapping("${ifdain.admin.base-path}/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SystemConfigService configService;
    private final AdminProperties adminProperties;
    private final PasswordEncoder passwordEncoder;
    private final DatabaseInitializer databaseInitializer;

    /**
     * 设置管理页面
     */
    @GetMapping
    public String settings(Model model, HttpServletRequest request) {
        List<SystemConfig> configs = configService.getAllEntries();
        model.addAttribute("configs", configs);
        model.addAttribute("basePath", adminProperties.getBasePath());

        // 服务器 URL (用于 Webhook 回调展示)
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        String serverUrl = scheme + "://" + host;
        if (port != 80 && port != 443) {
            serverUrl += ":" + port;
        }
        model.addAttribute("serverUrl", serverUrl);

        // 提取常用配置到单独变量方便模板展示
        model.addAttribute("userId", configService.getOrDefault(SystemConfigService.KEY_USER_ID, ""));
        model.addAttribute("apiToken", maskToken(configService.getOrDefault(SystemConfigService.KEY_API_TOKEN, "")));
        model.addAttribute("webhookPublicKey", maskToken(configService.getOrDefault(SystemConfigService.KEY_WEBHOOK_PUBLIC_KEY, "")));
        model.addAttribute("webhookPath", configService.getOrDefault(SystemConfigService.KEY_WEBHOOK_PATH, "/webhook/ifdian"));
        model.addAttribute("adminUsername", configService.getOrDefault(SystemConfigService.KEY_ADMIN_USERNAME, "admin"));
        model.addAttribute("apiBaseUrl", configService.getOrDefault(SystemConfigService.KEY_API_BASE_URL, "https://ifdian.net"));

        // Redis 配置
        model.addAttribute("redisEnabled", "true".equals(configService.getOrDefault(SystemConfigService.KEY_REDIS_ENABLED, "false")));
        model.addAttribute("redisHost", configService.getOrDefault(SystemConfigService.KEY_REDIS_HOST, "localhost"));
        model.addAttribute("redisPort", configService.getOrDefault(SystemConfigService.KEY_REDIS_PORT, "6379"));
        model.addAttribute("redisPassword", maskToken(configService.getOrDefault(SystemConfigService.KEY_REDIS_PASSWORD, "")));
        model.addAttribute("redisDatabase", configService.getOrDefault(SystemConfigService.KEY_REDIS_DATABASE, "0"));

        // 外部 API 配置
        model.addAttribute("externalApiKey", maskToken(configService.getOrDefault(SystemConfigService.KEY_EXTERNAL_API_KEY, "")));
        model.addAttribute("callbackMaxRetries", configService.getOrDefault(SystemConfigService.KEY_CALLBACK_MAX_RETRIES, "3"));
        model.addAttribute("callbackTimeoutMs", configService.getOrDefault(SystemConfigService.KEY_CALLBACK_TIMEOUT_MS, "10000"));

        // 数据库状态
        model.addAttribute("dbMode", databaseInitializer.getDbMode());
        model.addAttribute("dbProduct", databaseInitializer.getDbProductName());
        model.addAttribute("dbVersion", databaseInitializer.getDbVersion());
        model.addAttribute("dbReady", databaseInitializer.isTablesExist());

        return "admin/settings";
    }

    /**
     * 保存设置
     */
    @PostMapping("/save")
    public String saveSettings(@RequestParam(required = false) String userId,
                               @RequestParam(required = false) String apiToken,
                               @RequestParam(required = false) String webhookPublicKey,
                               @RequestParam(required = false) String webhookPath,
                               @RequestParam(required = false) String adminUsername,
                               @RequestParam(required = false) String adminPassword,
                               @RequestParam(required = false) String adminPasswordConfirm,
                               @RequestParam(required = false) String apiBaseUrl,
                               @RequestParam(required = false) String redisEnabled,
                               @RequestParam(required = false) String redisHost,
                               @RequestParam(required = false) String redisPort,
                               @RequestParam(required = false) String redisPassword,
                               @RequestParam(required = false) String redisDatabase,
                               @RequestParam(required = false) String externalApiKey,
                               @RequestParam(required = false) String callbackMaxRetries,
                               @RequestParam(required = false) String callbackTimeoutMs,
                               RedirectAttributes attr) {

        if (userId != null) {
            configService.set(SystemConfigService.KEY_USER_ID, userId, "爱发电 User ID");
        }
        if (apiToken != null && !apiToken.isEmpty()) {
            configService.set(SystemConfigService.KEY_API_TOKEN, apiToken, "爱发电 API Token");
        }
        if (webhookPublicKey != null) {
            configService.set(SystemConfigService.KEY_WEBHOOK_PUBLIC_KEY, webhookPublicKey, "Webhook RSA 公钥");
        }
        if (webhookPath != null) {
            configService.set(SystemConfigService.KEY_WEBHOOK_PATH, webhookPath, "Webhook 端点路径");
        }
        if (adminUsername != null) {
            configService.set(SystemConfigService.KEY_ADMIN_USERNAME, adminUsername, "管理员用户名");
        }
        if (adminPassword != null && !adminPassword.isEmpty()) {
            if (adminPassword.equals(adminPasswordConfirm)) {
                configService.set(SystemConfigService.KEY_ADMIN_PASSWORD, passwordEncoder.encode(adminPassword), "管理员密码(哈希)");
            } else {
                attr.addFlashAttribute("error", "两次输入的密码不一致，密码未修改");
            }
        }
        if (apiBaseUrl != null) {
            configService.set(SystemConfigService.KEY_API_BASE_URL, apiBaseUrl, "爱发电 API 基础地址");
        }

        // Redis 配置
        if (redisEnabled != null) {
            configService.set(SystemConfigService.KEY_REDIS_ENABLED, redisEnabled, "是否启用 Redis");
        }
        if (redisHost != null) {
            configService.set(SystemConfigService.KEY_REDIS_HOST, redisHost, "Redis 主机地址");
        }
        if (redisPort != null) {
            configService.set(SystemConfigService.KEY_REDIS_PORT, redisPort, "Redis 端口");
        }
        if (redisPassword != null && !redisPassword.isEmpty()) {
            configService.set(SystemConfigService.KEY_REDIS_PASSWORD, redisPassword, "Redis 密码");
        }
        if (redisDatabase != null) {
            configService.set(SystemConfigService.KEY_REDIS_DATABASE, redisDatabase, "Redis 数据库索引");
        }

        // 外部 API 配置
        if (externalApiKey != null) {
            configService.set(SystemConfigService.KEY_EXTERNAL_API_KEY, externalApiKey, "外部 API 访问密钥");
        }
        if (callbackMaxRetries != null) {
            configService.set(SystemConfigService.KEY_CALLBACK_MAX_RETRIES, callbackMaxRetries, "回调最大重试次数");
        }
        if (callbackTimeoutMs != null) {
            configService.set(SystemConfigService.KEY_CALLBACK_TIMEOUT_MS, callbackTimeoutMs, "回调 HTTP 超时(ms)");
        }

        attr.addFlashAttribute("success", "设置已保存");
        return "redirect:" + adminProperties.getBasePath() + "/settings";
    }

    /**
     * 对敏感 Token 做掩码处理（仅显示前后几位）
     */
    private String maskToken(String token) {
        if (token == null || token.length() < 8) {
            return token;
        }
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}
