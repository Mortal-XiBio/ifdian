package com.ifdain.repository;

import com.ifdain.entity.IfdianOrder;
import com.ifdain.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 爱发电订单数据访问层
 */
@Repository
public interface IfdianOrderRepository extends JpaRepository<IfdianOrder, Integer> {

    /**
     * 根据爱发电订单号查询
     */
    Optional<IfdianOrder> findByOutTradeNo(String outTradeNo);

    /**
     * 根据用户唯一标识查询 (OAuth2 user_private_id)
     */
    List<IfdianOrder> findByUserPrivateId(String userPrivateId);

    /**
     * 根据自定义订单ID查询
     */
    List<IfdianOrder> findByCustomOrderId(String customOrderId);

    /**
     * 根据用户ID查询
     */
    List<IfdianOrder> findByUserId(String userId);

    /**
     * 根据方案ID查询
     */
    List<IfdianOrder> findByPlanId(String planId);

    /**
     * 查询未处理的订单
     */
    List<IfdianOrder> findByProcessed(Integer processed);

    /**
     * 查询指定时间范围内已支付的订单
     */
    List<IfdianOrder> findByStatusAndReceivedAtBetween(OrderStatus status, LocalDateTime start, LocalDateTime end);

    /**
     * 查询是否存在指定订单号
     */
    boolean existsByOutTradeNo(String outTradeNo);

    // ==================== 管理后台统计方法 ====================

    /**
     * 根据状态统计订单数
     */
    long countByStatus(OrderStatus status);

    /**
     * 根据处理标记统计订单数
     */
    long countByProcessed(Integer processed);

    /**
     * 按创建时间降序分页查询
     */
    Page<IfdianOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 统计最近一段时间内已支付的订单数
     */
    @Query("SELECT COUNT(o) FROM IfdianOrder o WHERE o.receivedAt >= :since AND o.status = :status")
    long countRecentOrders(@Param("since") LocalDateTime since, @Param("status") OrderStatus status);

    /**
     * 统计最近一段时间内已支付的订单总金额
     */
    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM IfdianOrder o WHERE o.receivedAt >= :since AND o.status = :status")
    BigDecimal sumRecentAmount(@Param("since") LocalDateTime since, @Param("status") OrderStatus status);

    /**
     * 按方案汇总订单（指定时间范围内已支付的订单）
     */
    @Query("SELECT o.planId AS planId, COUNT(o) AS orderCount, COALESCE(SUM(o.totalAmount), 0) AS totalAmount " +
           "FROM IfdianOrder o WHERE o.receivedAt BETWEEN :start AND :end AND o.status = :status GROUP BY o.planId")
    List<PlanSummary> summarizeByPlan(@Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end,
                                       @Param("status") OrderStatus status);

    /**
     * 根据关键词搜索订单（模糊匹配订单号、用户ID、自定义订单ID）
     */
    @Query("SELECT o FROM IfdianOrder o WHERE o.outTradeNo LIKE %:keyword% " +
           "OR o.userId LIKE %:keyword% OR o.customOrderId LIKE %:keyword%")
    Page<IfdianOrder> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
