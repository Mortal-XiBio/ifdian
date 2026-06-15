package com.ifdain.service;

import com.ifdain.entity.IfdianOrder;

/**
 * 订单业务处理器接口
 *
 * <p>当 Webhook 接收到新订单并持久化后，会调用此处理器执行业务逻辑。
 * 宿主项目可注入自定义实现。</p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * @Component("myOrderProcessor")
 * public class MyOrderProcessor implements IfdianOrderProcessor {
 *     public void process(IfdianOrder order) { ... }
 * }
 * }</pre>
 *
 * 然后在 {@code application.yml} 中配置:
 * <pre>{@code
 * ifdain.order-processor-bean: myOrderProcessor
 * }</pre>
 */
@FunctionalInterface
public interface IfdianOrderProcessor {

    /**
     * 处理已持久化的订单
     *
     * @param order 已保存的订单实体
     */
    void process(IfdianOrder order);
}
