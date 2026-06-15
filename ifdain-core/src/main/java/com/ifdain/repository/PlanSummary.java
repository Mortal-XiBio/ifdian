package com.ifdain.repository;

/**
 * 方案汇总投影接口
 *
 * <p>用于 {@link IfdianOrderRepository#summarizeByPlan} 的 JPQL 查询投影，
 * 按方案ID汇总订单数量和金额。</p>
 */
public interface PlanSummary {

    /** 方案ID */
    String getPlanId();

    /** 订单数量 */
    Long getOrderCount();

    /** 总金额 */
    Number getTotalAmount();
}
