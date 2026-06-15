package com.ifdain.service;

import com.ifdain.entity.IfdainLog;
import com.ifdain.repository.IfdainLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 爱发电统一审计日志服务
 *
 * <p>将散落在各处的 {@code log.info/warn/error} 调用整合为统一入口，
 * 同时将关键事件持久化到 {@code ifdain_logs} 表，便于审计追溯。</p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * @Autowired
 * private IfdainLogService logService;
 *
 * logService.info("webhook", "Webhook received, order={}", outTradeNo);
 * logService.warn("api", "API returned non-200 ec={}", ec);
 * logService.error("order", "Failed to process order", outTradeNo, exception);
 * }</pre>
 *
 * <p>日志会同时输出到 SLF4J（应用日志文件）和数据库表 {@code ifdain_logs}。
 * 数据库写入为异步操作，不阻塞主业务流程。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IfdainLogService {

    private final IfdainLogRepository logRepository;

    // ==================== INFO ====================

    /**
     * 记录 INFO 级别日志
     *
     * @param source  事件来源 (如 webhook, order, api, system)
     * @param message 日志消息 (支持 SLF4J 风格的 {} 占位符)
     * @param args    消息参数
     */
    public void info(String source, String message, Object... args) {
        String formatted = formatMessage(message, args);
        log.info("[Ifdain][{}] {}", source, formatted);
        persistAsync("INFO", source, formatted, null, null);
    }

    /**
     * 记录 INFO 级别日志（关联订单号）
     *
     * @param source     事件来源
     * @param message    日志消息
     * @param outTradeNo 关联的爱发电订单号
     * @param args       消息参数
     */
    public void info(String source, String message, String outTradeNo, Object... args) {
        String formatted = formatMessage(message, args);
        log.info("[Ifdain][{}][{}] {}", source, outTradeNo, formatted);
        persistAsync("INFO", source, formatted, outTradeNo, null);
    }

    // ==================== WARN ====================

    /**
     * 记录 WARN 级别日志
     */
    public void warn(String source, String message, Object... args) {
        String formatted = formatMessage(message, args);
        log.warn("[Ifdain][{}] {}", source, formatted);
        persistAsync("WARN", source, formatted, null, null);
    }

    /**
     * 记录 WARN 级别日志（关联订单号）
     */
    public void warn(String source, String message, String outTradeNo, Object... args) {
        String formatted = formatMessage(message, args);
        log.warn("[Ifdain][{}][{}] {}", source, outTradeNo, formatted);
        persistAsync("WARN", source, formatted, outTradeNo, null);
    }

    // ==================== ERROR ====================

    /**
     * 记录 ERROR 级别日志（含异常栈）
     *
     * @param source  事件来源
     * @param message 日志消息
     * @param t       异常对象
     * @param args    消息参数
     */
    public void error(String source, String message, Throwable t, Object... args) {
        String formatted = formatMessage(message, args);
        String stack = stackTraceToString(t);
        log.error("[Ifdain][{}] {}", source, formatted, t);
        persistAsync("ERROR", source, formatted, null, stack);
    }

    /**
     * 记录 ERROR 级别日志（含异常栈，关联订单号）
     */
    public void error(String source, String message, String outTradeNo, Throwable t, Object... args) {
        String formatted = formatMessage(message, args);
        String stack = stackTraceToString(t);
        log.error("[Ifdain][{}][{}] {}", source, outTradeNo, formatted, t);
        persistAsync("ERROR", source, formatted, outTradeNo, stack);
    }

    /**
     * 记录 ERROR 级别日志（无异常，仅消息，关联订单号）
     */
    public void error(String source, String message, String outTradeNo, Object... args) {
        String formatted = formatMessage(message, args);
        log.error("[Ifdain][{}][{}] {}", source, outTradeNo, formatted);
        persistAsync("ERROR", source, formatted, outTradeNo, null);
    }

    // ==================== 快捷方法 ====================

    /** Webhook 相关日志 */
    public void logWebhook(String action, String outTradeNo, String detail) {
        info("webhook", "Webhook {} | order={} | {}", action, outTradeNo, detail);
    }

    /** 订单相关日志 */
    public void logOrder(String action, String outTradeNo, String detail) {
        info("order", "Order {} | order={} | {}", action, outTradeNo, detail);
    }

    /** API 调用相关日志 */
    public void logApi(String action, String detail, Object... args) {
        info("api", "API {} | {}", action, detail, args);
    }

    /** 系统启动/配置相关日志 */
    public void logSystem(String message, Object... args) {
        info("system", message, args);
    }

    // ==================== 内部方法 ====================

    /**
     * 格式化消息，将 {} 替换为实际参数（模仿 SLF4J 风格）
     */
    String formatMessage(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }
        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        int last = 0;
        while (last < message.length()) {
            int pos = message.indexOf("{}", last);
            if (pos == -1) {
                sb.append(message.substring(last));
                break;
            }
            sb.append(message, last, pos);
            if (argIndex < args.length) {
                sb.append(args[argIndex++]);
            } else {
                sb.append("{}");
            }
            last = pos + 2;
        }
        return sb.toString();
    }

    /**
     * 将异常栈转换为字符串
     */
    String stackTraceToString(Throwable t) {
        if (t == null) return null;
        StringWriter sw = new StringWriter(512);
        t.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        // 截断过长异常栈
        return trace.length() > 2000 ? trace.substring(0, 2000) + "\n... (truncated)" : trace;
    }

    /**
     * 异步持久化日志到数据库
     */
    @Async
    void persistAsync(String level, String source, String message,
                      String outTradeNo, String exception) {
        try {
            IfdainLog logEntry = IfdainLog.builder()
                    .level(level)
                    .source(source)
                    .message(message.length() > 512 ? message.substring(0, 512) : message)
                    .outTradeNo(outTradeNo)
                    .exception(exception)
                    .build();
            logRepository.save(logEntry);
        } catch (Exception e) {
            // 日志持久化失败不应影响主流程，仅打印警告
            log.warn("[Ifdain] Failed to persist audit log", e);
        }
    }
}
