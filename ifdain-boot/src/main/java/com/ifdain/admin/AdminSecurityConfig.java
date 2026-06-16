package com.ifdain.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * 管理后台 Spring Security 配置
 *
 * <p>提供表单登录保护管理后台页面，同时放行 Webhook 端点、静态资源和 H2 控制台。</p>
 *
 * <p>UserDetailsService 为动态实现：每次认证时实时从 SystemConfigService 读取密码，
 * 确保安装向导修改密码后立即生效，无需重启。</p>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AdminProperties.class)
@RequiredArgsConstructor
public class AdminSecurityConfig {

    private final AdminProperties adminProperties;
    private final SystemConfigService configService;
    @Value("${ifdain.webhook-path:/webhook/ifdian}")
    private String webhookPath;

    /**
     * Webhook 端点安全配置 — 无需认证、无需 CSRF
     */
    @Bean
    @Order(1)
    public SecurityFilterChain webhookFilterChain(HttpSecurity http) throws Exception {
        http
            .antMatcher(webhookPath + "/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers.frameOptions().sameOrigin());
        return http.build();
    }

    /**
     * 外部 API 安全配置 — 通过 API Key 认证，无需 CSRF
     */
    @Bean
    @Order(2)
    public SecurityFilterChain externalApiFilterChain(HttpSecurity http) throws Exception {
        http
            .antMatcher("/api/external/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * 管理后台 Security 过滤链
     */
    @Bean
    @Order(3)
    public SecurityFilterChain adminFilterChain(HttpSecurity http) throws Exception {
        String basePath = adminProperties.getBasePath(); // e.g. /admin

        http
            // 仅对管理后台路径启用安全
            .requestMatchers(matchers -> matchers
                .antMatchers(basePath + "/**", "/login", "/css/**", "/js/**")
            )
            .authorizeHttpRequests(auth -> auth
                // 静态资源、登录页、安装向导放行
                .antMatchers(
                    basePath + "/login",
                    basePath + "/setup",
                    basePath + "/setup/**",
                    basePath + "/test/**",
                    "/css/**",
                    "/js/**"
                ).permitAll()
                // H2 控制台 (embedded 模式开发用)
                .requestMatchers(PathRequest.toH2Console()).permitAll()
                // 其余所有请求需认证
                .anyRequest().authenticated()
            )
            // 表单登录
            .formLogin(form -> form
                .loginPage(basePath + "/login")
                .defaultSuccessUrl(basePath, true)
                .permitAll()
            )
            // 登出
            .logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher(basePath + "/logout"))
                .logoutSuccessUrl(basePath + "/login?logout")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .permitAll()
            )
            // CSRF: H2 控制台、管理后台内部操作、外部 API 免 CSRF
            // 管理后台已有表单登录保护，CSRF 对内部端点的额外安全价值有限
            .csrf(csrf -> csrf
                .ignoringRequestMatchers(PathRequest.toH2Console())
                .ignoringAntMatchers(
                    basePath + "/**",
                    "/api/external/**"
                )
            )
            // 允许 H2 控制台在 frame 中显示
            .headers(headers -> headers
                .frameOptions().sameOrigin()
            );

        return http.build();
    }

    /**
     * 动态 UserDetailsService — 每次认证时实时从数据库读取密码
     *
     * <p>安装完成后从 SystemConfigService 读取已哈希的密码，
     * 安装未完成时使用 application.yml 中的默认凭据。</p>
     */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        return username -> {
            if (configService.isSetupCompleted()) {
                // 安装已完成 → 从数据库读取当前密码（已哈希，含 {bcrypt} 前缀）
                String dbUsername = configService.getOrDefault(
                        SystemConfigService.KEY_ADMIN_USERNAME, adminProperties.getUsername());
                String dbPassword = configService.getOrDefault(
                        SystemConfigService.KEY_ADMIN_PASSWORD, "");

                if (username.equals(dbUsername)) {
                    return User.builder()
                            .username(dbUsername)
                            .password(dbPassword) // 已是 {bcrypt}... 格式，直接使用
                            .roles("ADMIN")
                            .build();
                }
            } else {
                // 安装未完成 → 使用 application.yml 默认凭据
                if (username.equals(adminProperties.getUsername())) {
                    return User.builder()
                            .username(username)
                            .password(passwordEncoder.encode(adminProperties.getPassword()))
                            .roles("ADMIN")
                            .build();
                }
            }
            throw new UsernameNotFoundException("用户不存在: " + username);
        };
    }

    /**
     * 密码编码器 (默认使用 BCrypt)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
