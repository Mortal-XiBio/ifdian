package com.ifdain.repository;

import com.ifdain.entity.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 系统配置项数据访问层
 */
@Repository
public interface SystemConfigRepository extends JpaRepository<SystemConfig, Integer> {

    /**
     * 根据配置键查询
     */
    Optional<SystemConfig> findByConfigKey(String configKey);

    /**
     * 根据配置键删除
     */
    void deleteByConfigKey(String configKey);
}
