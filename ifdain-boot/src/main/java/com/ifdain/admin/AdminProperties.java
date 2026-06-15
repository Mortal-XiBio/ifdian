package com.ifdain.admin;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.annotation.PostConstruct;

/**
 * 管理后台配置项
 *
 * <p>前缀: {@code ifdain.admin}</p>
 */
@Slf4j
@Data
@ConfigurationProperties(prefix = "ifdain.admin")
public class AdminProperties {

    /** 管理员登录用户名 (默认 admin) */
    private String username = "admin";

    /** 管理员登录密码 (默认 admin，首次安装后将被替换) */
    private String password = "admin";

    /** 管理后台基础路径 (默认 /admin) */
    private String basePath = "/admin";

    @PostConstruct
    void logWarning() {
        log.warn("[Ifdain] ===================================================");
        log.warn("[Ifdain] 首次部署请访问安装向导: {}/setup", basePath);
        log.warn("[Ifdain] 安装完成后默认密码将失效，使用安装时设置的密码登录");
        log.warn("[Ifdain] ===================================================");
    }
}
