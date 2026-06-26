package com.ifdain.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * 连接测试控制器
 *
 * <p>提供 Redis 和 MySQL 连接测试的 REST 端点，供前端 "测试连接" 按钮调用。</p>
 */
@Slf4j
@RestController
@RequestMapping("${ifdain.admin.base-path}/test")
@RequiredArgsConstructor
public class ConnectionTestController {

    private final DataSource dataSource;

    /**
     * 测试 MySQL 数据库连接
     */
    @PostMapping("/mysql")
    public ResponseEntity<Map<String, Object>> testMysqlConnection(
            @RequestParam String host,
            @RequestParam int port,
            @RequestParam String database,
            @RequestParam String username,
            @RequestParam String password) {

        Map<String, Object> result = new HashMap<>();

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            java.sql.DriverManager.setLoginTimeout(10);
            // 自动创建数据库（如不存在）
            try (Connection conn = getOrCreateDatabase(host, port, database, username, password)) {
                DatabaseMetaData meta = conn.getMetaData();
                result.put("success", true);
                result.put("message", "连接成功！"
                        + meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());
                result.put("product", meta.getDatabaseProductName());
                result.put("version", meta.getDatabaseProductVersion());
            }
        } catch (ClassNotFoundException e) {
            result.put("success", false);
            result.put("message", "MySQL 驱动未加载，请使用 standalone profile 启动或检查依赖");
            log.warn("[Ifdain] MySQL driver not found on classpath");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
            log.warn("[Ifdain] MySQL connection test failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 测试 Redis 连接
     */
    @PostMapping("/redis")
    public ResponseEntity<Map<String, Object>> testRedisConnection(
            @RequestParam String host,
            @RequestParam int port,
            @RequestParam(required = false) String password,
            @RequestParam(defaultValue = "0") int database) {

        Map<String, Object> result = new HashMap<>();

        try {
            // 清理 host：去空格和尾部斜杠
            String cleanHost = host.trim();
            while (cleanHost.endsWith("/")) {
                cleanHost = cleanHost.substring(0, cleanHost.length() - 1);
            }

            // 使用 Lettuce 客户端测试连接
            io.lettuce.core.RedisURI uri = io.lettuce.core.RedisURI.builder()
                    .withHost(cleanHost)
                    .withPort(port)
                    .withDatabase(database)
                    .build();

            if (password != null && !password.isEmpty()) {
                uri.setPassword(password.toCharArray());
            }

            io.lettuce.core.RedisClient client = io.lettuce.core.RedisClient.create(uri);
            io.lettuce.core.api.StatefulRedisConnection<String, String> connection = client.connect();
            String pong = connection.sync().ping();
            connection.close();
            client.shutdown();

            result.put("success", true);
            result.put("message", "Redis 连接成功！PING → " + pong);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
            log.warn("[Ifdain] Redis connection test failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 测试当前数据源连接（使用应用已配置的连接池）
     */
    @PostMapping("/datasource")
    public ResponseEntity<Map<String, Object>> testCurrentDataSource() {
        Map<String, Object> result = new HashMap<>();

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            result.put("success", true);
            result.put("message", "数据库连接正常");
            result.put("product", meta.getDatabaseProductName());
            result.put("version", meta.getDatabaseProductVersion());
            result.put("url", meta.getURL());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "数据库连接失败: " + e.getMessage());
            log.warn("[Ifdain] DataSource connection test failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 初始化数据库表（仅 standalone 模式）
     */
    @PostMapping("/init-db")
    public ResponseEntity<Map<String, Object>> initializeDatabase() {
        Map<String, Object> result = new HashMap<>();

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            if (meta.getDatabaseProductName().toLowerCase().contains("h2")) {
                result.put("success", true);
                result.put("message", "H2 嵌入式数据库，JPA 自动建表，无需手动初始化");
                return ResponseEntity.ok(result);
            }

            // MySQL: 检查表是否存在
            boolean tableExists = false;
            try (java.sql.ResultSet rs = meta.getTables(null, null, "system_config", new String[]{"TABLE"})) {
                tableExists = rs.next();
            }

            if (tableExists) {
                result.put("success", true);
                result.put("message", "数据表已存在，无需重复初始化");
            } else {
                // 执行建表脚本
                try {
                    org.springframework.core.io.ClassPathResource resource =
                            new org.springframework.core.io.ClassPathResource("sql/schema.sql");
                    org.springframework.jdbc.datasource.init.ResourceDatabasePopulator populator =
                            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator();
                    populator.addScript(resource);
                    populator.setContinueOnError(false);
                    populator.setSeparator(";");
                    populator.execute(dataSource);
                    result.put("success", true);
                    result.put("message", "数据表初始化成功！");
                } catch (Exception e) {
                    result.put("success", false);
                    result.put("message", "初始化失败: " + e.getMessage());
                    log.error("[Ifdain] Database initialization failed", e);
                }
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "获取数据库连接失败: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 远程初始化数据库表
     *
     * <p>使用前端提交的 MySQL 连接参数直连数据库并执行 schema.sql 建表脚本。
     * 适用于安装向导中用户在 H2 模式下提前初始化远程 MySQL 的场景。</p>
     */
    @PostMapping("/init-db-remote")
    public ResponseEntity<Map<String, Object>> initDbRemote(
            @RequestParam String host,
            @RequestParam int port,
            @RequestParam String database,
            @RequestParam String username,
            @RequestParam String password) {

        Map<String, Object> result = new HashMap<>();
        String url = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=true&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&connectTimeout=10000&socketTimeout=15000",
                host, port, database);

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            DriverManager.setLoginTimeout(10);

            // 获取连接（数据库不存在时自动创建）
            Connection conn = getOrCreateDatabase(host, port, database, username, password);

            try (conn) {
                DatabaseMetaData meta = conn.getMetaData();

                // 检查表是否已存在
                boolean tableExists = false;
                try (ResultSet rs = meta.getTables(null, null, "system_config", new String[]{"TABLE"})) {
                    tableExists = rs.next();
                }

                if (tableExists) {
                    result.put("success", true);
                    result.put("message", "数据表已存在，无需重复初始化");
                    return ResponseEntity.ok(result);
                }

                // 执行建表脚本
                ClassPathResource resource = new ClassPathResource("sql/schema.sql");
                ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
                populator.addScript(resource);
                populator.setContinueOnError(false);
                populator.setSeparator(";");
                populator.execute(new org.springframework.jdbc.datasource.SingleConnectionDataSource(conn, false));

                result.put("success", true);
                result.put("message", "MySQL 数据表初始化成功！(" + meta.getDatabaseProductName()
                        + " " + meta.getDatabaseProductVersion() + ")");
                log.info("[Ifdain] Remote DB init completed: {}:{}/{}", host, port, database);
            }
        } catch (ClassNotFoundException e) {
            result.put("success", false);
            result.put("message", "MySQL 驱动未加载（当前运行模式未包含 mysql-connector-java），请使用 standalone profile 启动或检查依赖");
            log.warn("[Ifdain] MySQL driver not found on classpath for remote init");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "初始化失败: " + e.getMessage());
            log.error("[Ifdain] Remote DB init failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 获取数据库连接，若数据库不存在则自动创建
     */
    private Connection getOrCreateDatabase(String host, int port, String database,
                                            String username, String password) throws Exception {
        String url = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=true&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&connectTimeout=10000&socketTimeout=15000",
                host, port, database);
        try {
            return DriverManager.getConnection(url, username, password);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Unknown database")) {
                String serverUrl = String.format("jdbc:mysql://%s:%d?useSSL=true&connectTimeout=10000&socketTimeout=15000&serverTimezone=Asia/Shanghai", host, port);
                try (Connection serverConn = DriverManager.getConnection(serverUrl, username, password);
                     java.sql.Statement stmt = serverConn.createStatement()) {
                    stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database.replace("`", "``") + "` DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci");
                    log.info("[Ifdain] Auto-created database: {}", database);
                }
                return DriverManager.getConnection(url, username, password);
            }
            throw e;
        }
    }
}
