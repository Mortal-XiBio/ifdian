package com.ifdain.repository;

import com.ifdain.entity.OAuth2Binding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * OAuth2 绑定数据访问层
 */
@Repository
public interface OAuth2BindingRepository extends JpaRepository<OAuth2Binding, Long> {

    /**
     * 根据爱发电用户私有标识查询绑定
     */
    Optional<OAuth2Binding> findByUserPrivateId(String userPrivateId);

    /**
     * 根据爱发电用户ID查询绑定
     */
    Optional<OAuth2Binding> findByUserId(String userId);

    /**
     * 根据本地用户ID查询绑定
     */
    Optional<OAuth2Binding> findByLocalUserId(String localUserId);

    /**
     * 检查用户是否已绑定
     */
    boolean existsByUserPrivateId(String userPrivateId);
}
