package com.ifdain.repository;

import com.ifdain.entity.PaymentRequest;
import com.ifdain.entity.PaymentRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 外部支付请求数据访问层
 */
@Repository
public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, Long> {

    /** 按请求 ID 查询 */
    Optional<PaymentRequest> findByRequestId(String requestId);

    /** 按自定义订单号查询（Webhook 匹配用） */
    Optional<PaymentRequest> findByCustomOrderId(String customOrderId);

    /** 检查自定义订单号是否已存在 */
    boolean existsByCustomOrderId(String customOrderId);

    /** 按状态查询（管理后台用） */
    List<PaymentRequest> findByStatus(PaymentRequestStatus status);

    /** 按状态查询，按创建时间降序 */
    List<PaymentRequest> findByStatusOrderByCreatedAtDesc(PaymentRequestStatus status);
}
