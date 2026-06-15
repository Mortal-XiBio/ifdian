package com.ifdain.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

/**
 * 数据库初始化检测与自动建表
 *
 * <p>启动时检测目标表是否存在，对于 standalone(MySQL) 模式，
 * 如果检测到空库则尝试从 classpath:sql/schema.sql 自动建表。</p>
 */
@Slf4j
@Component
public class DatabaseInitializer implements CommandLineRunner {

    private final DataSource dataSource;
    private final SystemConfigService configService;

    private volatile String dbMode = "unknown";
    private volatile boolean tablesExist = false;
    private volatile String dbProductName = "";
    private volatile String dbVersion = "";

    public DatabaseInitializer(DataSource dataSource,
                               SystemConfigService configService) {
        this.dataSource = dataSource;
        this.configService = configService;
    }

    @Override
    public void run(String... args) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            dbProductName = meta.getDatabaseProductName();
            dbVersion = meta.getDatabaseProductVersion();

            // 检测模式
            if (dbProductName.toLowerCase().contains("h2")) {
                dbMode = "embedded (H2)";
                // H2 使用 ddl-auto=update，JPA 自动建表，无需干预
                tablesExist = true;
                log.info("[Ifdain] 数据库模式: {} (JPA 自动建表)", dbMode);
            } else {
                dbMode = "standalone (MySQL)";

                // 检查核心表是否存在
                if (tableExists(conn, "ifdian_orders") && tableExists(conn, "system_config")) {
                    tablesExist = true;
                    log.info("[Ifdain] 数据库模式: {}, 表结构已就绪", dbMode);
                } else {
                    tablesExist = false;
                    log.warn("[Ifdain] 数据库模式: {}, 表结构缺失，尝试自动建表...", dbMode);

                    try {
                        runSchemaSql();
                        // 重新检查
                        try (Connection conn2 = dataSource.getConnection()) {
                            tablesExist = tableExists(conn2, "ifdian_orders")
                                    && tableExists(conn2, "system_config");
                        }
                        if (tablesExist) {
                            log.info("[Ifdain] 自动建表成功！");
                        } else {
                            log.warn("[Ifdain] 自动建表后仍未检测到表，请手动执行 sql/schema.sql");
                        }
                    } catch (Exception e) {
                        log.error("[Ifdain] 自动建表失败: {}", e.getMessage());
                        log.warn("[Ifdain] 请手动执行 sql/schema.sql 初始化数据库");
                    }
                }
            }

            // 将数据库信息写入系统配置
            configService.set("system.db_mode", dbMode, "数据库运行模式");
            configService.set("system.db_product", dbProductName, "数据库产品名称");
            configService.set("system.db_version", dbVersion, "数据库版本");
            configService.set("system.db_initialized", String.valueOf(tablesExist), "数据库是否已初始化");

        } catch (Exception e) {
            log.error("[Ifdain] 数据库初始化检测失败: {}", e.getMessage());
        }
    }

    private boolean tableExists(Connection conn, String tableName) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        // MySQL 表名大小写敏感取决于 OS，统一转大写匹配
        try (ResultSet rs = meta.getTables(null, null, tableName.toUpperCase(), new String[]{"TABLE"})) {
            if (rs.next()) return true;
        }
        try (ResultSet rs = meta.getTables(null, null, tableName.toLowerCase(), new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private void runSchemaSql() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("sql/schema.sql"));
        populator.setContinueOnError(true);
        populator.setSeparator(";");
        populator.execute(dataSource);
    }

    // ===== 状态查询方法 =====

    public String getDbMode() {
        return dbMode;
    }

    public boolean isTablesExist() {
        return tablesExist;
    }

    public String getDbProductName() {
        return dbProductName;
    }

    public String getDbVersion() {
        return dbVersion;
    }
}
