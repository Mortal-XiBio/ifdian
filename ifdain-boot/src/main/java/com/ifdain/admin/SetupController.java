package com.ifdain.admin;

import com.ifdain.config.IfdainProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 安装向导控制器
 *
 * <p>首次运行时引导用户完成基础配置：环境检测、管理员账号、爱发电 API、Redis(可选)、Webhook。</p>
 */
@Slf4j
@Controller
@RequestMapping("${ifdain.admin.base-path}/setup")
@RequiredArgsConstructor
public class SetupController {

    private final SystemConfigService configService;
    private final AdminProperties adminProperties;
    private final PasswordEncoder passwordEncoder;
    private final DatabaseInitializer databaseInitializer;
    private final IfdainProperties ifdainProperties;

    // ===== 公共辅助方法 =====

    private void addCommonModel(Model model) {
        model.addAttribute("basePath", adminProperties.getBasePath());
        model.addAttribute("dbMode", databaseInitializer.getDbMode());
        model.addAttribute("dbProduct", databaseInitializer.getDbProductName());
        model.addAttribute("dbVersion", databaseInitializer.getDbVersion());
        model.addAttribute("dbReady", databaseInitializer.isTablesExist());
    }

    private boolean redirectIfDone() {
        return configService.isSetupCompleted();
    }

    /**
     * 安装向导 - 步骤1: 创建管理员账号
     */
    @GetMapping
    public String setupStep1(Model model) {
        if (redirectIfDone()) {
            return "redirect:" + adminProperties.getBasePath();
        }
        addCommonModel(model);
        model.addAttribute("step", 1);
        return "admin/setup";
    }

    /**
     * 步骤1提交: 创建管理员
     */
    @PostMapping("/step1")
    public String doStep1(@RequestParam String username,
                          @RequestParam String password,
                          @RequestParam String confirmPassword,
                          RedirectAttributes attr) {
        if (redirectIfDone()) {
            return "redirect:" + adminProperties.getBasePath();
        }

        if (!password.equals(confirmPassword)) {
            attr.addFlashAttribute("error", "两次输入的密码不一致");
            return "redirect:" + adminProperties.getBasePath() + "/setup";
        }
        if (username.trim().isEmpty() || password.trim().isEmpty()) {
            attr.addFlashAttribute("error", "用户名和密码不能为空");
            return "redirect:" + adminProperties.getBasePath() + "/setup";
        }
        if (password.length() < 6) {
            attr.addFlashAttribute("error", "密码长度不能少于 6 位");
            return "redirect:" + adminProperties.getBasePath() + "/setup";
        }

        configService.set(SystemConfigService.KEY_ADMIN_USERNAME, username.trim(), "管理员用户名");
        configService.set(SystemConfigService.KEY_ADMIN_PASSWORD, passwordEncoder.encode(password.trim()), "管理员密码(哈希)");

        attr.addFlashAttribute("setupStep", 2);
        return "redirect:" + adminProperties.getBasePath() + "/setup/step2";
    }

    /**
     * 步骤2页面: 爱发电 API
     */
    @GetMapping("/step2")
    public String setupStep2(Model model) {
        if (redirectIfDone()) {
            return "redirect:" + adminProperties.getBasePath();
        }
        addCommonModel(model);
        model.addAttribute("step", 2);
        return "admin/setup";
    }

    /**
     * 步骤2提交: 爱发电 API 配置
     */
    @PostMapping("/step2")
    public String doStep2(@RequestParam String userId,
                          @RequestParam String apiToken,
                          RedirectAttributes attr) {
        if (redirectIfDone()) {
            return "redirect:" + adminProperties.getBasePath();
        }

        configService.set(SystemConfigService.KEY_USER_ID, userId.trim(), "爱发电 User ID");
        configService.set(SystemConfigService.KEY_API_TOKEN, apiToken.trim(), "爱发电 API Token");

        // 同步运行时配置，使 API 客户端立即可用
        ifdainProperties.setUserId(userId.trim());
        ifdainProperties.setApiToken(apiToken.trim());

        attr.addFlashAttribute("setupStep", 3);
        return "redirect:" + adminProperties.getBasePath() + "/setup/step3";
    }

    /**
     * 步骤3页面: Redis 缓存配置 (可选)
     */
    @GetMapping("/step3")
    public String setupStep3(Model model) {
        if (redirectIfDone()) {
            return "redirect:" + adminProperties.getBasePath();
        }
        addCommonModel(model);
        model.addAttribute("step", 3);
        return "admin/setup";
    }

    /**
     * 步骤3提交: Redis 配置
     */
    @PostMapping("/step3")
    public String doStep3(@RequestParam(defaultValue = "false") boolean redisEnabled,
                          @RequestParam(defaultValue = "localhost") String redisHost,
                          @RequestParam(defaultValue = "6379") int redisPort,
                          @RequestParam(required = false) String redisPassword,
                          @RequestParam(defaultValue = "0") int redisDatabase,
                          RedirectAttributes attr) {
        if (redirectIfDone()) {
            return "redirect:" + adminProperties.getBasePath();
        }

        configService.set(SystemConfigService.KEY_REDIS_ENABLED, String.valueOf(redisEnabled), "是否启用 Redis");
        configService.set(SystemConfigService.KEY_REDIS_HOST, redisHost.trim(), "Redis 主机地址");
        configService.set(SystemConfigService.KEY_REDIS_PORT, String.valueOf(redisPort), "Redis 端口");
        configService.set(SystemConfigService.KEY_REDIS_PASSWORD,
                redisPassword != null ? redisPassword.trim() : "", "Redis 密码");
        configService.set(SystemConfigService.KEY_REDIS_DATABASE, String.valueOf(redisDatabase), "Redis 数据库索引");

        attr.addFlashAttribute("setupStep", 4);
        return "redirect:" + adminProperties.getBasePath() + "/setup/step4";
    }

    /**
     * 步骤4页面: Webhook 配置
     */
    @GetMapping("/step4")
    public String setupStep4(Model model) {
        if (redirectIfDone()) {
            return "redirect:" + adminProperties.getBasePath();
        }
        addCommonModel(model);
        model.addAttribute("step", 4);
        return "admin/setup";
    }

    /**
     * 步骤4提交: Webhook 配置
     */
    @PostMapping("/step4")
    public String doStep4(@RequestParam(required = false) String webhookPublicKey,
                          @RequestParam(defaultValue = "/webhook/ifdian") String webhookPath,
                          RedirectAttributes attr) {
        if (redirectIfDone()) {
            return "redirect:" + adminProperties.getBasePath();
        }

        configService.set(SystemConfigService.KEY_WEBHOOK_PUBLIC_KEY,
                webhookPublicKey != null ? webhookPublicKey.trim() : "",
                "Webhook RSA 公钥");
        configService.set(SystemConfigService.KEY_WEBHOOK_PATH,
                webhookPath.trim(), "Webhook 端点路径");

        // 标记安装完成
        configService.markSetupCompleted();

        attr.addFlashAttribute("setupComplete", true);
        return "redirect:" + adminProperties.getBasePath() + "/setup/complete";
    }

    /**
     * 安装完成页面
     */
    @GetMapping("/complete")
    public String setupComplete(Model model) {
        if (redirectIfDone()) {
            return "redirect:" + adminProperties.getBasePath();
        }
        addCommonModel(model);
        model.addAttribute("step", 5);
        return "admin/setup";
    }

    /**
     * 跳过安装向导（仅开发调试用，安装完成后不可用）
     */
    @PostMapping("/skip")
    public String skipSetup(RedirectAttributes attr) {
        if (redirectIfDone()) {
            return "redirect:" + adminProperties.getBasePath();
        }
        configService.markSetupCompleted();
        attr.addFlashAttribute("setupComplete", true);
        return "redirect:" + adminProperties.getBasePath() + "/setup/complete";
    }

    /**
     * 保存 MySQL 配置
     *
     * <p>将 MySQL 连接参数保存到 system_config 表，同时写入 ./config/application-standalone.yml，
     * 用户重启时指定 --spring.profiles.active=standalone 即可自动加载。</p>
     */
    @PostMapping("/save-mysql")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveMysqlConfig(
            @RequestParam String host,
            @RequestParam int port,
            @RequestParam String database,
            @RequestParam String username,
            @RequestParam String password) {

        Map<String, Object> result = new HashMap<>();

        try {
            // 1. 保存到 system_config 表
            configService.set("mysql.host", host.trim(), "MySQL 主机地址");
            configService.set("mysql.port", String.valueOf(port), "MySQL 端口");
            configService.set("mysql.database", database.trim(), "MySQL 数据库名");
            configService.set("mysql.username", username.trim(), "MySQL 用户名");
            configService.set("mysql.password", password.trim(), "MySQL 密码");

            // 2. 写入 ./config/application-standalone.yml
            //    同时包含已配置的 API 凭证，确保重启后 AfdianApiClient 可用
            String savedUserId = configService.getOrDefault(SystemConfigService.KEY_USER_ID, "your_user_id_here");
            String savedApiToken = configService.getOrDefault(SystemConfigService.KEY_API_TOKEN, "your_api_token_here");

            String ymlContent = String.format(
                    "# Ifdain MySQL standalone 配置 (由安装向导自动生成)%n" +
                    "# 使用方式: java -jar ifdain.jar --spring.profiles.active=standalone%n" +
                    "spring:%n" +
                    "  datasource:%n" +
                    "    url: jdbc:mysql://%s:%d/%s?useSSL=true&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&connectTimeout=10000&socketTimeout=15000%n" +
                    "    driver-class-name: com.mysql.cj.jdbc.Driver%n" +
                    "    username: %s%n" +
                    "    password: %s%n" +
                    "  jpa:%n" +
                    "    database-platform: org.hibernate.dialect.MySQL8Dialect%n" +
                    "    hibernate:%n" +
                    "      ddl-auto: validate%n" +
                    "    show-sql: false%n" +
                    "ifdain:%n" +
                    "  user-id: %s%n" +
                    "  api-token: %s%n" +
                    "  admin:%n" +
                    "    base-path: /admin%n",
                    host.trim(), port, database.trim(), username.trim(), password.trim(),
                    savedUserId, savedApiToken);

            Path configDir = Paths.get(System.getProperty("user.dir"), "config");
            Files.createDirectories(configDir);
            Path ymlFile = configDir.resolve("application-standalone.yml");
            Files.write(ymlFile, ymlContent.getBytes(StandardCharsets.UTF_8));

            log.info("[Ifdain] MySQL 配置已保存: {}", ymlFile.toAbsolutePath());

            result.put("success", true);
            result.put("message", "MySQL 配置已保存到 " + ymlFile.toAbsolutePath()
                    + "。请使用 --spring.profiles.active=standalone 参数重启应用。");
        } catch (Exception e) {
            log.error("[Ifdain] 保存 MySQL 配置失败", e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }
}
