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

                // 在方案设置页定位空卡片或添加新卡片
                log.info("[BrowserAuto] Looking for empty plan card...");
                int cardIndex = findOrCreateEmptyCard(page);
                if (cardIndex < 0) {
                    byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    return PlanCreationResult.failed(
                            "无法找到空的方案编辑卡片，爱发电页面结构可能已变化",
                            screenshot
                    );
                }

                // 填写方案创建表单（在目标卡片内）
                log.info("[BrowserAuto] Filling plan creation form (card {}): {}", cardIndex, request.getTitle());
                boolean filled = fillPlanCreationForm(page, request, cardIndex);
                if (!filled) {
                    byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    return PlanCreationResult.failed(
                            "方案表单填写失败，部分字段可能不匹配",
                            screenshot
                    );
                }

                // 设置网络响应拦截 — 在提交前注册，从保存 API 响应中捕获 plan_id
                java.util.concurrent.atomic.AtomicReference<String> interceptedPlanId =
                        new java.util.concurrent.atomic.AtomicReference<>();
                page.onResponse(response -> {
                    try {
                        String url = response.url();
                        if (url.contains("/api/") && (url.contains("plan") || url.contains("save"))) {
                            String body = response.text();
                            log.debug("[BrowserAuto] Intercepted API: {} -> {}", url,
                                    body.length() > 500 ? body.substring(0, 500) + "..." : body);
                            Matcher m2 = Pattern.compile("\"plan_id\"\\s*:\\s*\"([a-f0-9]{20,})\"")
                                    .matcher(body);
                            if (m2.find()) {
                                interceptedPlanId.set(m2.group(1));
                                log.info("[BrowserAuto] Captured plan_id from API: {}", m2.group(1));
                            }
                        }
                    } catch (Exception ignored) {
                    }
                });

                // 提交（点击目标卡片内的保存按钮）
                log.info("[BrowserAuto] Submitting plan (card {})...", cardIndex);
                boolean submitted = submitPlanForm(page, cardIndex);
                if (!submitted) {
                    byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    return PlanCreationResult.failed(
                            "方案提交失败",
                            screenshot
                    );
                }

                // 等待 Vue 处理保存请求 + 页面更新
                page.waitForTimeout(3000);

                // 优先使用网络拦截拿到的 plan_id，其次从页面 DOM 提取
                String planId = interceptedPlanId.get();
                if (planId == null || planId.isBlank()) {
                    planId = extractPlanId(page);
                }

                // 截图保存后的页面状态
                byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));

                if (planId != null && !planId.isBlank()) {
                    log.info("[BrowserAuto] Plan created successfully, plan_id={}", planId);
                    return PlanCreationResult.builder()
                            .success(true).planId(planId).message("方案创建成功")
                            .screenshotBase64(Base64.getEncoder().encodeToString(screenshot))
                            .build();
                }

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

    // ==================== 方案删除 / 隐藏 ====================

    /**
     * 删除方案
     *
     * @param title 方案名称（用于定位卡片）
     */
    public PlanCreationResult deletePlan(String title) {
        String cookieJson = configService.getOrDefault(KEY_AFDIAN_COOKIE, "");
        if (cookieJson.isBlank()) {
            return PlanCreationResult.failed("未配置爱发电登录 Cookie");
        }

        try (Playwright playwright = Playwright.create(
                new Playwright.CreateOptions()
                        .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
                            .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox")));
            try {
                BrowserContext context = browser.newContext();
                Page page = context.newPage();

                int injected = injectCookies(context, cookieJson);
                if (injected <= 0) {
                    return PlanCreationResult.failed("Cookie 注入失败");
                }

                page.navigate(AFDIAN_PLAN_SETTINGS);
                page.waitForLoadState();
                page.waitForTimeout(3000);

                if (isLoginRequired(page)) {
                    byte[] ss = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    return PlanCreationResult.needLogin("Cookie 已失效", ss);
                }

                int cardIndex = findCardByTitle(page, title);
                if (cardIndex < 0) {
                    byte[] ss = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    return PlanCreationResult.failed("未找到名称为「" + title + "」的方案", ss);
                }

                // 点击卡片内的删除按钮（X 图标）
                List<ElementHandle> cards = page.querySelectorAll(".vm-plan-edit");
                ElementHandle card = cards.get(cardIndex);
                ElementHandle delBtn = card.querySelector("i.afd.afd-x.del-x-1");
                if (delBtn == null) {
                    delBtn = card.querySelector("i.afd-x");
                }
                if (delBtn == null || !delBtn.isVisible()) {
                    byte[] ss = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    return PlanCreationResult.failed("未找到删除按钮", ss);
                }

                delBtn.click();
                page.waitForTimeout(1500);

                // 爱发电删除确认：优先处理 Vue 自定义弹窗 (vue-dialog)，其次处理浏览器原生 confirm
                try {
                    // 处理浏览器原生 confirm() 弹窗
                    page.onDialog(dialog -> {
                        log.info("[BrowserAuto] Dialog appeared: type={}, message={}", dialog.type(), dialog.message());
                        dialog.accept();
                    });

                    // 处理 Vue 自定义弹窗 (.vue-dialog)
                    ElementHandle confirmBtn = page.querySelector("button.vue-dialog-button:has-text('确认')");
                    if (confirmBtn == null) {
                        confirmBtn = page.querySelector(".vue-dialog-button:has-text('确认')");
                    }
                    if (confirmBtn == null) {
                        confirmBtn = page.querySelector("button:has-text('确认')");
                    }
                    if (confirmBtn == null) {
                        confirmBtn = page.querySelector("div.vm-btn:has-text('确认')");
                    }
                    if (confirmBtn == null) {
                        confirmBtn = page.querySelector("button:has-text('确定')");
                    }
                    if (confirmBtn != null && confirmBtn.isVisible()) {
                        confirmBtn.click();
                        log.info("[BrowserAuto] Clicked delete confirm button");
                        page.waitForTimeout(2000);
                    } else {
                        log.warn("[BrowserAuto] No confirm button found in dialog");
                    }
                } catch (Exception e) {
                    log.warn("[BrowserAuto] Error handling delete dialog: {}", e.getMessage());
                }

                page.waitForTimeout(1500);
                byte[] ss = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                log.info("[BrowserAuto] Plan deleted: {}", title);
                return PlanCreationResult.builder()
                        .success(true).message("方案「" + title + "」已删除")
                        .screenshotBase64(Base64.getEncoder().encodeToString(ss)).build();
            } finally {
                browser.close();
            }
        } catch (Exception e) {
            log.error("[BrowserAuto] deletePlan failed", e);
            return PlanCreationResult.failed("浏览器自动化异常: " + e.getMessage());
        }
    }

    /**
     * 切换方案的隐藏/显示状态
     *
     * @param title 方案名称（用于定位卡片）
     */
    public PlanCreationResult toggleHidePlan(String title) {
        String cookieJson = configService.getOrDefault(KEY_AFDIAN_COOKIE, "");
        if (cookieJson.isBlank()) {
            return PlanCreationResult.failed("未配置爱发电登录 Cookie");
        }

        try (Playwright playwright = Playwright.create(
                new Playwright.CreateOptions()
                        .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
                            .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox")));
            try {
                BrowserContext context = browser.newContext();
                Page page = context.newPage();

                int injected = injectCookies(context, cookieJson);
                if (injected <= 0) {
                    return PlanCreationResult.failed("Cookie 注入失败");
                }

                page.navigate(AFDIAN_PLAN_SETTINGS);
                page.waitForLoadState();
                page.waitForTimeout(3000);

                if (isLoginRequired(page)) {
                    byte[] ss = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    return PlanCreationResult.needLogin("Cookie 已失效", ss);
                }

                int cardIndex = findCardByTitle(page, title);
                if (cardIndex < 0) {
                    byte[] ss = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    return PlanCreationResult.failed("未找到名称为「" + title + "」的方案", ss);
                }

                // 点击卡片内的隐藏/显示切换按钮
                List<ElementHandle> cards = page.querySelectorAll(".vm-plan-edit");
                ElementHandle card = cards.get(cardIndex);
                ElementHandle toggleBtn = card.querySelector("div.afd-eye-off-box");
                if (toggleBtn == null) {
                    toggleBtn = card.querySelector(".afd-eye-off-box");
                }
                if (toggleBtn == null || !toggleBtn.isVisible()) {
                    byte[] ss = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    return PlanCreationResult.failed("未找到隐藏/显示切换按钮", ss);
                }

                toggleBtn.click();
                page.waitForTimeout(1500);

                byte[] ss = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                log.info("[BrowserAuto] Plan hide toggled: {}", title);
                return PlanCreationResult.builder()
                        .success(true).message("方案「" + title + "」隐藏状态已切换")
                        .screenshotBase64(Base64.getEncoder().encodeToString(ss)).build();
            } finally {
                browser.close();
            }
        } catch (Exception e) {
            log.error("[BrowserAuto] toggleHidePlan failed", e);
            return PlanCreationResult.failed("浏览器自动化异常: " + e.getMessage());
        }
    }

    /**
     * 根据方案名称定位卡片，返回卡片索引
     */
    private int findCardByTitle(Page page, String title) {
        if (title == null || title.isBlank()) {
            return -1;
        }
        List<ElementHandle> cards = page.querySelectorAll(".vm-plan-edit");
        for (int i = 0; i < cards.size(); i++) {
            ElementHandle card = cards.get(i);
            ElementHandle titleInput = card.querySelector("input[placeholder*='赞助昵称']");
            if (titleInput != null) {
                String value = titleInput.inputValue();
                if (title.equals(value)) {
                    log.info("[BrowserAuto] Found card '{}' at index {}", title, i);
                    return i;
                }
            }
        }
        log.warn("[BrowserAuto] Card not found for title: {}", title);
        return -1;
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
     * 点击「增加订阅方案」按钮创建新的方案编辑卡片
     *
     * <p>始终通过点击「增加订阅方案」按钮来新建卡片，不会复用已有卡片。
     * 已有方案（包括有付款记录的）不应被覆盖。</p>
     *
     * @return 目标卡片在 .vm-plan-edit 列表中的索引，-1 表示失败
     */
    private int findOrCreateEmptyCard(Page page) {
        // 先记录当前卡片数量
        List<ElementHandle> cardsBefore = page.querySelectorAll(".vm-plan-edit");
        int countBefore = cardsBefore.size();
        log.info("[BrowserAuto] Current plan cards: {}", countBefore);

        // 始终点击「增加订阅方案」按钮添加新卡片
        ElementHandle addBtn = page.querySelector("div.vm-btn:has-text('增加订阅方案')");
        if (addBtn == null) {
            try {
                addBtn = page.querySelector(":has-text('增加订阅方案')");
            } catch (Exception ignored) {
            }
        }

        if (addBtn == null || !addBtn.isVisible()) {
            log.error("[BrowserAuto] '增加订阅方案' button not found or not visible");
            return -1;
        }

        addBtn.click();
        page.waitForTimeout(2000); // 等待 Vue 渲染新卡片
        log.info("[BrowserAuto] Clicked '增加订阅方案' to add new card");

        // 新卡片应该是列表的最后一个
        List<ElementHandle> cardsAfter = page.querySelectorAll(".vm-plan-edit");
        if (cardsAfter.size() > countBefore) {
            int newIndex = cardsAfter.size() - 1;
            log.info("[BrowserAuto] New card created at index {} (was {})", newIndex, countBefore);
            return newIndex;
        }

        // 如果卡片数没变，可能是新卡片替换了某个空卡片
        // 尝试找到标题为空的新卡片
        for (int i = 0; i < cardsAfter.size(); i++) {
            ElementHandle titleInput = cardsAfter.get(i).querySelector("input[placeholder*='赞助昵称']");
            if (titleInput != null) {
                String value = titleInput.inputValue();
                if (value == null || value.isBlank()) {
                    log.info("[BrowserAuto] Found empty card at index {} after clicking add", i);
                    return i;
                }
            }
        }

        log.warn("[BrowserAuto] Clicked add but no new/empty card appeared");
        return -1;
    }

    /**
     * 在目标方案卡片内填写表单字段
     *
     * <p>爱发电方案卡片使用以下选择器（无 name 属性）：</p>
     * <ul>
     *   <li>方案名称: input[placeholder*='赞助昵称']</li>
     *   <li>价格: input.money-input 或 input[placeholder='00.00']</li>
     *   <li>描述: textarea[placeholder*='奖励详情']</li>
     * </ul>
     */
    private boolean fillPlanCreationForm(Page page, PlanCreationRequest request, int cardIndex) {
        List<ElementHandle> cards = page.querySelectorAll(".vm-plan-edit");
        if (cardIndex >= cards.size()) {
            log.warn("[BrowserAuto] Card index {} out of range (total: {})", cardIndex, cards.size());
            return false;
        }

        ElementHandle card = cards.get(cardIndex);
        boolean anyFilled = false;

        // 方案名称
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            anyFilled |= tryFillInCard(card, request.getTitle(),
                    "input[placeholder*='赞助昵称']",
                    "input.gl-input.w75");
        }

        // 价格
        if (request.getPrice() != null) {
            anyFilled |= tryFillInCard(card, String.valueOf(request.getPrice()),
                    "input[placeholder='00.00']",
                    "input.money-input",
                    "input[inputmode='decimal']");
        }

        // 方案描述
        if (request.getDescription() != null && !request.getDescription().isBlank()) {
            anyFilled |= tryFillInCard(card, request.getDescription(),
                    "textarea[placeholder*='奖励详情']",
                    "textarea.gl-textarea");
        }

        return anyFilled;
    }

    /**
     * 在方案卡片内尝试用多个选择器定位并填写字段
     */
    private boolean tryFillInCard(ElementHandle card, String value, String... selectors) {
        for (String selector : selectors) {
            try {
                ElementHandle element = card.querySelector(selector);
                if (element != null && element.isVisible()) {
                    element.click(); // 先点击聚焦
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
     * 点击目标方案卡片内的「保存」按钮
     *
     * <p>爱发电的保存按钮是 &lt;div class="vm-btn look3 pt12 pb12"&gt;保存&lt;/div&gt;，
     * 不是 &lt;button&gt; 元素。</p>
     */
    private boolean submitPlanForm(Page page, int cardIndex) {
        List<ElementHandle> cards = page.querySelectorAll(".vm-plan-edit");
        if (cardIndex >= cards.size()) {
            log.warn("[BrowserAuto] Card index {} out of range for submit", cardIndex);
            return false;
        }

        ElementHandle card = cards.get(cardIndex);

        // 方案卡片内的保存按钮: div.vm-btn.look3.pt12.pb12 包含文本「保存」
        String[] saveSelectors = {
                "div.vm-btn:has-text('保存')",
                ".vm-btn.look3.pt12.pb12",
                "div:has-text('保存')"
        };

        for (String selector : saveSelectors) {
            try {
                ElementHandle btn = card.querySelector(selector);
                if (btn != null && btn.isVisible()) {
                    btn.click();
                    log.info("[BrowserAuto] Clicked save button: {}", selector);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        // 最后尝试：使用 Playwright 全局文本选择器
        try {
            ElementHandle btn = page.querySelector("div.vm-btn.look3.pt12.pb12");
            if (btn != null && btn.isVisible()) {
                btn.click();
                log.info("[BrowserAuto] Clicked save button via global selector");
                return true;
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    /**
     * 从页面提取 plan_id
     *
     * <p>爱发电方案设置是 Vue SPA，保存后不会跳转页面，plan_id 不会出现在 URL 中。
     * 尝试从页面内容、Vue 数据或 DOM 属性中提取。</p>
     */
    private String extractPlanId(Page page) {
        String url = page.url();
        String content = page.content();

        // 1. 从 URL 中提取 (不太可能，但保留)
        Pattern urlPattern = Pattern.compile("/plan/([a-f0-9]{32,})");
        Matcher m = urlPattern.matcher(url);
        if (m.find()) {
            return m.group(1);
        }

        // 2. 从页面内容中提取（Vue 渲染后的数据）
        Pattern contentPattern = Pattern.compile("plan_id[\"']?\\s*[:=]\\s*[\"']([a-f0-9]{32,})[\"']");
        m = contentPattern.matcher(content);
        if (m.find()) {
            return m.group(1);
        }

        // 3. 从 Vue 组件的 data 属性中提取
        Pattern dataPattern = Pattern.compile("data-plan[_-]?id[\"']?\\s*[=:\"']\\s*[\"']?([a-f0-9]{32,})");
        m = dataPattern.matcher(content);
        if (m.find()) {
            return m.group(1);
        }

        // 4. 尝试通过元素选择器
        try {
            ElementHandle el = page.querySelector("[data-plan-id]");
            if (el != null) {
                return el.getAttribute("data-plan-id");
            }
        } catch (Exception ignored) {
        }

        // 5. 尝试从页面 JavaScript 中提取 Vue 数据
        try {
            Object result = page.evaluate(
                    "() => { try { var plans = document.querySelector('.vm-block-plan').__vue__; " +
                    "if (plans && plans.plans) return plans.plans.map(p => p.plan_id).join(','); " +
                    "} catch(e) {} return null; }");
            if (result != null) {
                String ids = String.valueOf(result);
                if (!ids.isBlank()) {
                    // 返回最新的（最后一个）
                    String[] idArr = ids.split(",");
                    return idArr[idArr.length - 1].trim();
                }
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
