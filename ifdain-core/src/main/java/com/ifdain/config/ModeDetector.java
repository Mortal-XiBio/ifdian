package com.ifdain.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

/**
 * 运行模式自动检测器
 *
 * <p>根据已配置的数据源自动判断当前模式：
 * <ul>
 *   <li>H2 数据源 → embedded 模式</li>
 *   <li>MySQL 数据源 → standalone 模式</li>
 * </ul>
 */
@Slf4j
public class ModeDetector {

    private ModeDetector() {}

    /**
     * 自动检测运行模式
     *
     * @param dataSource 已配置的数据源
     * @return "embedded" 或 "standalone"
     */
    public static String detect(DataSource dataSource) {
        if (dataSource == null) {
            log.warn("[Ifdain] DataSource is null, fallback to embedded mode");
            return "embedded";
        }

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String url = meta.getURL();
            String driver = meta.getDriverName();

            log.info("[Ifdain] DataSource URL: {}, Driver: {}", url, driver);

            if (url != null && (url.startsWith("jdbc:h2") || url.contains(":h2:"))) {
                return "embedded";
            } else if (url != null && url.startsWith("jdbc:mysql")) {
                return "standalone";
            } else if (driver != null && driver.toLowerCase().contains("h2")) {
                return "embedded";
            } else if (driver != null && driver.toLowerCase().contains("mysql")) {
                return "standalone";
            } else {
                log.warn("[Ifdain] Unknown DataSource type (url={}, driver={}), default to embedded", url, driver);
                return "embedded";
            }
        } catch (Exception e) {
            log.warn("[Ifdain] Failed to detect DataSource type, fallback to embedded", e);
            return "embedded";
        }
    }

    /**
     * 检测 JDK 版本号 (用于兼容性校验)
     */
    public static int detectJdkVersion() {
        String version = System.getProperty("java.version");
        if (version == null) return 8;

        // "1.8.0_xxx" → 8, "11.0.1" → 11, "17.0.1" → 17
        if (version.startsWith("1.")) {
            return Integer.parseInt(version.substring(2, 3));
        }
        int dotIdx = version.indexOf('.');
        if (dotIdx > 0) {
            return Integer.parseInt(version.substring(0, dotIdx));
        }
        return Integer.parseInt(version);
    }

    /**
     * 检测 MySQL 版本 (仅 standalone 模式)
     */
    public static String detectMysqlVersion(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            return meta.getDatabaseProductVersion();
        } catch (Exception e) {
            log.warn("[Ifdain] Failed to detect MySQL version", e);
            return "unknown";
        }
    }
}
