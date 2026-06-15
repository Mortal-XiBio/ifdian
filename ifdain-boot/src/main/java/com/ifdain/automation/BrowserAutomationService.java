package com.ifdain.automation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.ifdain.admin.SystemConfigService;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 浏览器自动化服务 — 通过 Playwright 模拟操作爱发电网页
 *
 * <p>作为 iframe 嵌入方案的 fallback，当爱发电页面禁止被嵌套时，
 * 由服务端启动无头浏览器自动完成方案创建。</p>
 *
 * <h3>前提条件</h3>
 * <ul>
 *   <li>服务器需安装 Chromium 浏览器: {@code playwright install chromium}</li>
 *   <li>用户需先在「系统设置」中配置爱发电登录 Cookie</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserAutomationService {

    private final SystemConfigService configService;

    /** 爱发电登录 Cookie 存储键 */
    public static final String KEY_AFDIAN_COOKIE = "ifdain.afdian_cookie";
    /** 爱发电方案管理地址 */
    private static final String AFDIAN_PLAN_SETTINGS = "https://ifdian.net/setting/plan";
    /** 爱发电登录地址 */
    private static final String AFDIAN_LOGIN = "https://ifdian.net/login";

    // ==================== 方案创建 ====================

    /**
     * 创建赞助方案
     *
     * @param request 方案参数
     * @return 创建结果 (含 plan_id 或错误信息)
     */
    public PlanCreationResult createPlan(PlanCreationRequest request) {
        String cookieJson = configService.getOrDefault(KEY_AFDIAN_COOKIE, "");
        if (cookieJson.isBlank()) {
            return PlanCreationResult.failed("未配置爱发电登录 Cookie，请先在系统设置中配置");
        }

        try (Playwright playwright = Playwright.create(
                new Playwright.CreateOptions()
                        .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
                            .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox"))
            );

            try {
                BrowserContext context = browser.newContext();
                Page page = context.newPage();

                // 注入 Cookie 实现登录态
                int injectedCount = injectCookies(context, cookieJson);
                if (injectedCount <= 0) {
                    String reason = injectedCount == -1
                            ? "Cookie 格式无法解析。请确保格式为 JSON 数组 [{name,value,...}] 或 document.cookie 字符串 \"key1=val1; key2=val2\""
                            : "Cookie 解析后为空（0 条有效 Cookie），请检查 Cookie 内容";
                    return PlanCreationResult.failed(reason);
                }

                // 打开方案设置页面
                log.info("[BrowserAuto] Navigating to plan settings: {}", AFDIAN_PLAN_SETTINGS);
                page.navigate(AFDIAN_PLAN_SETTINGS);

                // 等待页面加载 + Vue SPA 渲染完成
                page.waitForLoadState();
                page.waitForTimeout(3000); // 等待 Vue SPA 异步渲染

                // 检查是否需要登录
                if (isLoginRequired(page)) {
                    // 截图供用户排查
                    byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    return PlanCreationResult.needLogin(
                            "Cookie 已失效，需要重新登录爱发电。请在浏览器中登录后导出 Cookie。",
                            screenshot
                    );
                }

                // 导航到方案创建页面
                log.info("[BrowserAuto] Navigating to plan creation page...");
                boolean navigated = navigateToPlanCreation(page);
                if (!navigated) {
                    byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    return PlanCreationResult.failed(
                            "无法定位方案创建入口，爱发电页面结构可能已变化",
                            screenshot
                    );
                }

                // 填写创建表单
                log.info("[BrowserAuto] Filling plan creation form: {}", request.getTitle());
                boolean filled = fillPlanCreationForm(page, request);
                if (!filled) {
                    byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    return PlanCreationResult.failed(
                            "方案表单填写失败，部分字段可能不匹配",
                            screenshot
                    );
                }

                // 提交
                log.info("[BrowserAuto] Submitting plan...");
                boolean submitted = submitPlanForm(page);
                if (!submitted) {
                    byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    return PlanCreationResult.failed(
                            "方案提交失败",
                            screenshot
                    );
                }

                // 等待跳转并提取 plan_id
                page.waitForTimeout(3000);
                String planId = extractPlanId(page);

                if (planId != null && !planId.isBlank()) {
                    log.info("[BrowserAuto] Plan created successfully, plan_id={}", planId);
                    return PlanCreationResult.success(planId, "方案创建成功");
                }

                // 尝试从页面中查找
                byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                return PlanCreationResult.failed(
                        "方案可能已创建，但无法自动提取 plan_id。请查看截图确认",
                        screenshot
                );

            } finally {
                browser.close();
            }
        } catch (Exception e) {
            log.error("[BrowserAuto] Plan creation failed", e);
            return PlanCreationResult.failed("浏览器自动化异常: " + e.getMessage());
        }
    }

    /**
     * 测试浏览器自动化是否可用
     */
    public boolean isAvailable() {
        try (Playwright playwright = Playwright.create(
                new Playwright.CreateOptions()
                        .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true)
            );
            browser.close();
            return true;
        } catch (Exception e) {
            log.warn("[BrowserAuto] Playwright not available: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 注入 Cookie 到浏览器上下文
     *
     * <p>支持多种 Cookie 格式：</p>
     * <ul>
     *   <li>JSON 数组: [{name, value, domain, path, httpOnly, secure}, ...]</li>
     *   <li>JSON 单对象: {name, value, domain, path, ...}</li>
     *   <li>document.cookie 格式: "name1=value1; name2=value2; ..."</li>
     * </ul>
     *
     * @return 注入的 Cookie 数量，-1 表示解析失败
     */
    private int injectCookies(BrowserContext context, String cookieJson) {
        if (cookieJson == null || cookieJson.isBlank()) {
            log.warn("[BrowserAuto] Cookie JSON is empty");
            return 0;
        }

        String trimmed = cookieJson.trim();

        // Format 1 & 2: JSON (array or single object)
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return injectCookiesFromJson(context, trimmed);
        }

        // Format 3: document.cookie 格式 "name1=value1; name2=value2"
        if (trimmed.contains("=") && !trimmed.contains("\"")) {
            return injectCookiesFromDocumentCookie(context, trimmed);
        }

        log.warn("[BrowserAuto] Unrecognized cookie format (doesn't look like JSON or document.cookie)");
        return -1;
    }

    /**
     * 从 JSON 格式注入 Cookie
     */
    private int injectCookiesFromJson(BrowserContext context, String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, Object>> cookieList;

            if (json.startsWith("[")) {
                cookieList = mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            } else {
                // Single object → wrap in list
                Map<String, Object> single = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                cookieList = Collections.singletonList(single);
            }

            List<Cookie> cookies = new ArrayList<>();
            for (Map<String, Object> c : cookieList) {
                String name = String.valueOf(c.get("name"));
                String value = String.valueOf(c.get("value"));
                if (name == null || name.isBlank() || "null".equals(name)) {
                    continue;
                }

                Cookie cookie = new Cookie(name, value);

                // 设置 domain: 优先用 JSON 中的，否则默认 .ifdian.net
                String domain = c.containsKey("domain") && !"null".equals(String.valueOf(c.get("domain")))
                        ? String.valueOf(c.get("domain"))
                        : ".ifdian.net";
                cookie.setDomain(domain);

                // 设置 path: 优先用 JSON 中的，否则默认 /
                String path = c.containsKey("path") && !"null".equals(String.valueOf(c.get("path")))
                        ? String.valueOf(c.get("path"))
                        : "/";
                cookie.setPath(path);

                cookie.setHttpOnly(Boolean.TRUE.equals(c.get("httpOnly")));
                cookie.setSecure(Boolean.TRUE.equals(c.get("secure")));

                // sameSite 属性（Playwright 支持）
                if (c.containsKey("sameSite")) {
                    try {
                        String sameSite = String.valueOf(c.get("sameSite")).toLowerCase();
                        if ("lax".equals(sameSite)) {
                            cookie.setSameSite(com.microsoft.playwright.options.SameSiteAttribute.LAX);
                        } else if ("strict".equals(sameSite)) {
                            cookie.setSameSite(com.microsoft.playwright.options.SameSiteAttribute.STRICT);
                        } else if ("none".equals(sameSite)) {
                            cookie.setSameSite(com.microsoft.playwright.options.SameSiteAttribute.NONE);
                        }
                    } catch (Exception ignored) {
                    }
                }

                cookies.add(cookie);
            }

            if (cookies.isEmpty()) {
                log.warn("[BrowserAuto] Parsed 0 valid cookies from JSON");
                return 0;
            }

            context.addCookies(cookies);
            log.info("[BrowserAuto] Injected {} cookies from JSON (domains: {})",
                    cookies.size(),
                    cookies.stream().map(c -> c.domain).distinct().collect(java.util.stream.Collectors.joining(", ")));
            return cookies.size();
        } catch (Exception e) {
            log.warn("[BrowserAuto] Failed to parse cookie JSON: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 从 document.cookie 格式注入 Cookie（name1=value1; name2=value2）
     */
    private int injectCookiesFromDocumentCookie(BrowserContext context, String cookieStr) {
        try {
            String[] pairs = cookieStr.split(";");
            List<Cookie> cookies = new ArrayList<>();

            for (String pair : pairs) {
                pair = pair.trim();
                if (pair.isEmpty()) continue;

                int eqIdx = pair.indexOf('=');
                if (eqIdx <= 0) continue;

                String name = pair.substring(0, eqIdx).trim();
                String value = pair.substring(eqIdx + 1).trim();
                if (name.isEmpty()) continue;

                Cookie cookie = new Cookie(name, value);
                cookie.setDomain(".ifdian.net");
                cookie.setPath("/");
                cookies.add(cookie);
            }

            if (cookies.isEmpty()) {
                log.warn("[BrowserAuto] Parsed 0 cookies from document.cookie string");
                return 0;
            }

            context.addCookies(cookies);
            log.info("[BrowserAuto] Injected {} cookies from document.cookie format", cookies.size());
            return cookies.size();
        } catch (Exception e) {
            log.warn("[BrowserAuto] Failed to parse document.cookie string: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 检查当前页面是否需要登录（Cookie 失效/未认证）
     *
     * <p>检测多种未登录迹象：URL 重定向到登录页、页面内容中的登录提示、
     * Vue SPA 渲染的登录组件等。</p>
     */
    private boolean isLoginRequired(Page page) {
        String url = page.url();

        // 1. URL 包含 /login — 明确的登录页重定向
        if (url.contains("/login")) {
            log.info("[BrowserAuto] Detected login redirect: {}", url);
            return true;
        }

        // 2. 检查页面内容
        String content = page.content().toLowerCase();

        // 中文登录提示
        if (content.contains("请登录") || content.contains("请先登录")
                || content.contains("未登录") || content.contains("尚未登录")
                || content.contains("登录后查看") || content.contains("立即登录")) {
            log.info("[BrowserAuto] Detected login prompt in page content");
            return true;
        }

        // 英文登录提示
        if (content.contains("sign in") || content.contains("log in")
                || content.contains("please login") || content.contains("sign in to continue")) {
            log.info("[BrowserAuto] Detected login prompt (English) in page content");
            return true;
        }

        // 3. 检测 ifdian.net 的 Vue SPA 登录组件特征
        //    (页面中包含登录表单但没有用户信息时)
        if (content.contains("vm-login") || content.contains("login-form")
                || content.contains("class=\"login")) {
            // 进一步确认：没有用户相关的已登录标志
            if (!content.contains("nav-header") || !content.contains("avatar")) {
                log.info("[BrowserAuto] Detected login page via Vue component markers");
                return true;
            }
        }

        // 4. 检测 "用户不存在" 等错误（可能是 Cookie 中的 user_id 无效）
        if (content.contains("用户不存在") || content.contains("账号不存在")
                || content.contains("user not found")) {
            log.info("[BrowserAuto] Detected 'user not found' error");
            return true;
        }

        return false;
    }

    /**
     * 在创作中心页面定位方案创建入口
     */
    private boolean navigateToPlanCreation(Page page) {
        // 如果已经在 plan settings 页面 (如 /setting/plan)，尝试找到创建/新建按钮
        if (page.url().contains("/setting/plan")) {
            String[] createSelectors = {
                    "button:has-text('创建方案')",
                    "a:has-text('创建方案')",
                    "button:has-text('新建方案')",
                    "a:has-text('新建方案')",
                    "button:has-text('添加方案')",
                    "a:has-text('添加方案')",
                    "a[href*='/plan/create']",
                    "a[href*='/plan/new']",
                    ".create-plan-btn",
                    "[data-action='create-plan']",
                    "button:has-text('发布')",
                    "a:has-text('发布方案')"
            };
            for (String selector : createSelectors) {
                try {
                    ElementHandle element = page.querySelector(selector);
                    if (element != null && element.isVisible()) {
                        element.click();
                        page.waitForLoadState();
                        log.info("[BrowserAuto] Clicked plan creation entry: {}", selector);
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
            // 如果页面上就有创建表单 (如直接在 setting/plan 页面内嵌创建表单)，也算成功
            if (page.querySelector("form") != null) {
                log.info("[BrowserAuto] Already on a page with a form, treating as creation page");
                return true;
            }
        }

        // 尝试常见的导航选择器
        String[] selectors = {
                "a[href*='/plan/create']",
                "a[href*='/plan/new']",
                "a[href*='create-plan']",
                "button:has-text('创建方案')",
                "a:has-text('新建方案')",
                "a:has-text('添加方案')",
                ".create-plan-btn",
                "[data-action='create-plan']"
        };

        for (String selector : selectors) {
            try {
                ElementHandle element = page.querySelector(selector);
                if (element != null && element.isVisible()) {
                    element.click();
                    page.waitForLoadState();
                    log.info("[BrowserAuto] Clicked plan creation entry: {}", selector);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        // 尝试直接导航到常见的创建页 URL
        String[] createUrls = {
                "https://ifdian.net/setting/plan",
                "https://ifdian.net/creator/plan/create",
                "https://ifdian.net/creator/plan/new",
                "https://ifdian.net/dashboard/plan/create",
                "https://ifdian.net/dashboard/plan/new",
                "https://ifdian.net/plan/create",
                "https://ifdian.net/plan/new"
        };

        for (String url : createUrls) {
            try {
                page.navigate(url);
                page.waitForLoadState();
                // 检查是否成功到达创建页 (不是重定向回登录页)
                if (!page.url().contains("/login")) {
                    log.info("[BrowserAuto] Navigated to: {}", url);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    /**
     * 填写方案创建表单
     */
    private boolean fillPlanCreationForm(Page page, PlanCreationRequest request) {
        boolean anyFilled = false;

        // 方案名称
        if (request.getTitle() != null) {
            anyFilled |= tryFill(page, request.getTitle(),
                    "input[name='title']", "input[name='name']",
                    "input[placeholder*='方案']", "input[placeholder*='名称']",
                    "#plan-title", "#plan-name");
        }

        // 价格
        if (request.getPrice() != null) {
            anyFilled |= tryFill(page, String.valueOf(request.getPrice()),
                    "input[name='price']", "input[name='amount']",
                    "input[placeholder*='金额']", "input[placeholder*='价格']",
                    "#plan-price", "#plan-amount");
        }

        // 方案描述
        if (request.getDescription() != null) {
            anyFilled |= tryFill(page, request.getDescription(),
                    "textarea[name='description']", "textarea[name='desc']",
                    "textarea[placeholder*='描述']", "textarea[placeholder*='介绍']",
                    "#plan-description", "#plan-desc");
        }

        return anyFilled;
    }

    /**
     * 尝试用多个选择器定位并填写表单字段
     */
    private boolean tryFill(Page page, String value, String... selectors) {
        for (String selector : selectors) {
            try {
                ElementHandle element = page.querySelector(selector);
                if (element != null && element.isVisible()) {
                    element.fill(value);
                    log.info("[BrowserAuto] Filled {} -> {}", selector, value);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * 提交方案表单
     */
    private boolean submitPlanForm(Page page) {
        String[] submitSelectors = {
                "button[type='submit']",
                "button:has-text('提交')",
                "button:has-text('保存')",
                "button:has-text('创建')",
                "button:has-text('发布')",
                "input[type='submit']",
                ".submit-btn",
                "#submit"
        };

        for (String selector : submitSelectors) {
            try {
                ElementHandle button = page.querySelector(selector);
                if (button != null && button.isVisible()) {
                    button.click();
                    page.waitForTimeout(2000);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    /**
     * 从页面提取 plan_id
     */
    private String extractPlanId(Page page) {
        String url = page.url();
        String content = page.content();

        // 从 URL 中提取: /plan/xxx 或 plan_id=xxx
        Pattern urlPattern = Pattern.compile("/plan/([a-f0-9]{32,})");
        Matcher m = urlPattern.matcher(url);
        if (m.find()) {
            return m.group(1);
        }

        // 从页面内容中提取
        Pattern contentPattern = Pattern.compile("plan_id[\"']?\\s*[:=]\\s*[\"']([a-f0-9]{32,})[\"']");
        m = contentPattern.matcher(content);
        if (m.find()) {
            return m.group(1);
        }

        // 尝试通过元素选择器
        try {
            ElementHandle el = page.querySelector("[data-plan-id]");
            if (el != null) {
                return el.getAttribute("data-plan-id");
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    // ==================== 数据类 ====================

    @Data
    @Builder
    public static class PlanCreationRequest {
        /** 方案名称 */
        private String title;
        /** 月付价格 (元) */
        private Double price;
        /** 方案描述 */
        private String description;
    }

    @Data
    @Builder
    public static class PlanCreationResult {
        private boolean success;
        private String planId;
        private String message;
        private boolean needLogin;
        /** Base64 编码的截图 (失败时提供) */
        private String screenshotBase64;

        public static PlanCreationResult success(String planId, String message) {
            return PlanCreationResult.builder()
                    .success(true).planId(planId).message(message).build();
        }

        public static PlanCreationResult failed(String message) {
            return PlanCreationResult.builder()
                    .success(false).message(message).build();
        }

        public static PlanCreationResult failed(String message, byte[] screenshot) {
            return PlanCreationResult.builder()
                    .success(false).message(message)
                    .screenshotBase64(screenshot != null ? Base64.getEncoder().encodeToString(screenshot) : null)
                    .build();
        }

        public static PlanCreationResult needLogin(String message, byte[] screenshot) {
            return PlanCreationResult.builder()
                    .success(false).needLogin(true).message(message)
                    .screenshotBase64(screenshot != null ? Base64.getEncoder().encodeToString(screenshot) : null)
                    .build();
        }
    }
}
