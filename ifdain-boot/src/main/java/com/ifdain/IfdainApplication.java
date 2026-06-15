package com.ifdain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 爱发电支付集成 — Spring Boot 启动入口
 *
 * <p>使用方式: 运行此 main 方法，默认以 embedded 模式启动 (H2 数据库)。</p>
 * <p>如需 standalone 模式 (MySQL)，使用 {@code --spring.profiles.active=standalone}。</p>
 */
@SpringBootApplication
public class IfdainApplication {

    public static void main(String[] args) {
        SpringApplication.run(IfdainApplication.class, args);
    }
}
