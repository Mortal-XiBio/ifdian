package com.ifdain.config;

import com.ifdain.service.DefaultOrderProcessor;
import com.ifdain.service.IfdianOrderProcessor;
import com.ifdain.service.IfdianWebhookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;

/**
 * 爱发电自动配置
 *
 * <p>自动检测运行模式 (embedded/standalone)，并注册核心 Bean。</p>
 *
 * <h3>使用方式</h3>
 * <p>宿主项目引入 {@code ifdain-core} 依赖后，在 {@code application.yml} 中配置
 * {@code ifdain.user-id} 和 {@code ifdain.api-token} 即可使用。</p>
 *
 * <p>如需自定义订单处理逻辑，实现 {@link IfdianOrderProcessor} 并配置
 * {@code ifdain.order-processor-bean}。</p>
 */
@Slf4j
@Configuration
@EnableAsync
@ConditionalOnClass(IfdianWebhookService.class)
@EnableConfigurationProperties(IfdainProperties.class)
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@ComponentScan(basePackages = "com.ifdain")
public class IfdainAutoConfiguration {

    @Autowired
    private IfdainProperties properties;

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 运行模式检测并输出日志
     *
     * <p>运行模式由 DataSource 类型自动检测，无需手动配置。</p>
     */
    @Bean
    public ModeReporter modeReporter(DataSource dataSource) {
        String mode = ModeDetector.detect(dataSource);
        int jdkVersion = ModeDetector.detectJdkVersion();

        log.info("========================================");
        log.info("[Ifdain] Mode          : {}", mode);
        log.info("[Ifdain] JDK Version   : {}", jdkVersion);
        log.info("[Ifdain] Webhook Path  : {}", properties.getWebhookPath());
        log.info("[Ifdain] API Base URL  : {}", properties.getApiBaseUrl());

        if ("standalone".equals(mode)) {
            String mysqlVer = ModeDetector.detectMysqlVersion(dataSource);
            log.info("[Ifdain] MySQL Version : {}", mysqlVer);
        }

        log.info("========================================");

        return new ModeReporter(mode, jdkVersion);
    }

    /**
     * 注册默认订单处理器
     *
     * <p>如果容器中已有 {@link IfdianOrderProcessor} 实现，则不创建此默认 Bean。</p>
     */
    @Bean
    @ConditionalOnMissingBean(IfdianOrderProcessor.class)
    public DefaultOrderProcessor defaultOrderProcessor() {
        return new DefaultOrderProcessor();
    }

    /**
     * 回调 HTTP 客户端
     *
     * <p>用于向外部程序发送支付成功回调。超时时间由 {@code ifdain.callback-timeout-ms} 配置。</p>
     */
    @Bean
    @ConditionalOnMissingBean(RestTemplate.class)
    public RestTemplate restTemplate() {
        int timeout = properties.getCallbackTimeoutMs();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }

    /**
     * 模式报告记录 (仅内部使用)
     */
    public record ModeReporter(String mode, int jdkVersion) {}
}
