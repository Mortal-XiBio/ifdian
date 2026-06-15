package com.ifdain.service;

import com.ifdain.entity.IfdianOrder;
import lombok.extern.slf4j.Slf4j;

/**
 * 默认订单处理器 — 仅标记已处理，不做业务操作
 *
 * <p>实体在 {@code handleWebhook()} 的事务边界内为托管态，JPA 脏检查会自动持久化，
 * 无需显式调用 {@code save()}。</p>
 *
 * <p>宿主项目可通过实现 {@link IfdianOrderProcessor} 并配置
 * {@code ifdain.order-processor-bean} 来替换此默认实现。</p>
 */
@Slf4j
public class DefaultOrderProcessor implements IfdianOrderProcessor {

    @Override
    public void process(IfdianOrder order) {
        log.info("[Ifdain] Default processor: marking order {} as processed", order.getOutTradeNo());
        order.setProcessed(1);
    }
}
