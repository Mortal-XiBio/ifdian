package com.ifdain.admin;

import com.ifdain.entity.IfdianOrder;
import com.ifdain.entity.OrderStatus;
import com.ifdain.repository.IfdianOrderRepository;
import com.ifdain.service.AfdianApiClient;
import com.ifdain.service.IfdianWebhookService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 管理后台页面控制器
 *
 * <p>提供订单展示、搜索和仪表盘功能。</p>
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AdminController {

    private final IfdianOrderRepository orderRepository;
    private final AdminProperties adminProperties;
    private final SystemConfigService configService;
    private final AfdianApiClient apiClient;
    private final IfdianWebhookService webhookService;
    private final ObjectMapper objectMapper;

    /**
     * 登录页面 — 安装未完成时自动跳转到安装向导
     */
    @GetMapping("${ifdain.admin.base-path}/login")
    public String login(Model model) {
        // 安装未完成 → 直接进入安装向导（无需登录）
        if (!configService.isSetupCompleted()) {
            return "redirect:" + adminProperties.getBasePath() + "/setup";
        }
        model.addAttribute("basePath", adminProperties.getBasePath());
        return "admin/login";
    }

    /**
     * 仪表盘 — 展示概览统计数据
     */
    @GetMapping("${ifdain.admin.base-path}")
    public String dashboard(Model model) {
        // 未完成安装时重定向到安装向导
        if (!configService.isSetupCompleted()) {
            return "redirect:" + adminProperties.getBasePath() + "/setup";
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last7days = now.minusDays(7);
        LocalDateTime last30days = now.minusDays(30);

        // 总订单数 & 总金额
        long totalOrders = orderRepository.count();
        OrderStatus paidStatus = OrderStatus.PAID;
        long paidOrders = orderRepository.countByStatus(paidStatus);
        long pendingOrders = orderRepository.countByProcessed(0);

        // 最近 7 天
        long recent7Orders = orderRepository.countRecentOrders(last7days, paidStatus);
        double recent7Amount = Optional.ofNullable(orderRepository.sumRecentAmount(last7days, paidStatus))
                .map(v -> v.doubleValue()).orElse(0.0);

        // 最近 30 天
        long recent30Orders = orderRepository.countRecentOrders(last30days, paidStatus);
        double recent30Amount = Optional.ofNullable(orderRepository.sumRecentAmount(last30days, paidStatus))
                .map(v -> v.doubleValue()).orElse(0.0);

        // 按方案汇总（最近30天已支付的订单）
        var planSummary = orderRepository.summarizeByPlan(last30days, now, paidStatus);

        model.addAttribute("totalOrders", totalOrders);
        model.addAttribute("paidOrders", paidOrders);
        model.addAttribute("pendingOrders", pendingOrders);
        model.addAttribute("recent7Orders", recent7Orders);
        model.addAttribute("recent7Amount", String.format("%.2f", recent7Amount));
        model.addAttribute("recent30Orders", recent30Orders);
        model.addAttribute("recent30Amount", String.format("%.2f", recent30Amount));
        model.addAttribute("planSummary", planSummary);
        model.addAttribute("basePath", adminProperties.getBasePath());

        return "admin/dashboard";
    }

    /**
     * 订单列表 — 分页展示，支持搜索
     */
    @GetMapping("${ifdain.admin.base-path}/orders")
    public String orders(@RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "20") int size,
                         @RequestParam(required = false) String keyword,
                         Model model) {
        Pageable pageable = PageRequest.of(page, size);
        Page<IfdianOrder> orderPage;

        if (keyword != null && !keyword.trim().isEmpty()) {
            orderPage = orderRepository.searchByKeyword(keyword.trim(), pageable);
        } else {
            orderPage = orderRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        model.addAttribute("orders", orderPage.getContent());
        model.addAttribute("currentPage", orderPage.getNumber());
        model.addAttribute("totalPages", orderPage.getTotalPages());
        model.addAttribute("totalElements", orderPage.getTotalElements());
        model.addAttribute("keyword", keyword);
        model.addAttribute("basePath", adminProperties.getBasePath());

        return "admin/orders";
    }

    /**
     * 订单详情
     */
    @GetMapping("${ifdain.admin.base-path}/orders/{id}")
    public String orderDetail(@PathVariable Integer id, Model model) {
        IfdianOrder order = orderRepository.findById(id)
                .orElse(null);
        model.addAttribute("order", order);
        model.addAttribute("basePath", adminProperties.getBasePath());
        return "admin/order-detail";
    }

    /**
     * 从爱发电 API 拉取订单并入库
     */
    @PostMapping("${ifdain.admin.base-path}/orders/sync")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> syncOrders(
            @RequestParam(defaultValue = "1") int startPage,
            @RequestParam(defaultValue = "100") int perPage,
            @RequestParam(defaultValue = "0") int maxPages) {

        Map<String, Object> result = new HashMap<>();
        int totalFetched = 0;
        int newSaved = 0;
        int skipped = 0;
        int pagesProcessed = 0;
        StringBuilder errors = new StringBuilder();

        int page = startPage;
        try {
            while (true) {
                JsonNode apiResult = apiClient.queryOrders(page, perPage);
                if (apiResult == null || apiResult.path("ec").asInt() != 200) {
                    String em = apiResult != null ? apiResult.path("em").asText("") : "no response";
                    errors.append("第 ").append(page).append(" 页: ").append(em).append("; ");
                    break;
                }

                JsonNode list = apiResult.path("data").path("list");
                int totalPage = apiResult.path("data").path("total_page").asInt();

                if (!list.isArray() || list.isEmpty()) {
                    break;
                }

                for (JsonNode orderNode : list) {
                    totalFetched++;
                    try {
                        String rawJson = objectMapper.writeValueAsString(orderNode);
                        var saved = webhookService.saveOrderFromApi(orderNode, rawJson);
                        if (saved != null) {
                            newSaved++;
                        } else {
                            skipped++;
                        }
                    } catch (Exception e) {
                        errors.append("保存失败: ").append(e.getMessage()).append("; ");
                    }
                }

                pagesProcessed++;
                log.info("[Ifdain] Orders sync page {}: fetched={}, new={}", page, totalFetched, newSaved);

                if (page >= totalPage || (maxPages > 0 && pagesProcessed >= maxPages)) {
                    break;
                }
                page++;
                Thread.sleep(200);
            }
        } catch (Exception e) {
            log.error("[Ifdain] Orders sync failed", e);
            errors.append("异常: ").append(e.getMessage());
        }

        result.put("success", true);
        result.put("total_fetched", totalFetched);
        result.put("new_saved", newSaved);
        result.put("skipped", skipped);
        result.put("pages_processed", pagesProcessed);
        String msg = String.format("同步完成：共拉取 %d 条，新增入库 %d 条，跳过已有 %d 条", totalFetched, newSaved, skipped);
        if (errors.length() > 0) {
            msg += "（错误: " + errors.toString() + "）";
        }
        result.put("message", msg);

        return ResponseEntity.ok(result);
    }
}
