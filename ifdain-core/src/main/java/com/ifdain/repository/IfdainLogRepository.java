package com.ifdain.repository;

import com.ifdain.entity.IfdainLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 审计日志数据访问
 */
@Repository
public interface IfdainLogRepository extends JpaRepository<IfdainLog, Long> {

    /** 按来源查询，按创建时间倒序 */
    List<IfdainLog> findBySourceOrderByCreatedAtDesc(String source);

    /** 按订单号查询 */
    List<IfdainLog> findByOutTradeNoOrderByCreatedAtDesc(String outTradeNo);
}
