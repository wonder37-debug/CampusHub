package com.campushub.backend.auth.repository;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import java.util.List;
import java.util.Optional;

public interface UserRepository {

    /**
     * 按主键查询用户，不存在时返回空。
     */
    Optional<User> findById(Long id);

    /**
     * 按学号查询用户。数据库实现需保证学号唯一。
     */
    Optional<User> findByStudentId(String studentId);

    /**
     * 按邮箱查询用户。数据库实现需保证邮箱唯一。
     */
    Optional<User> findByEmail(String email);

    /**
     * 按登录标识查询用户。当前登录标识允许邮箱或学号。
     */
    Optional<User> findByLoginId(String loginId);

    /**
     * 查询全量用户。当前主要供后台管理统计与列表使用。
     */
    List<User> findAll();

    /**
     * 按用户状态查询。主要供后台管理使用，Service 层负责权限校验。
     */
    List<User> findByStatus(UserStatus status);

    /**
     * 按用户角色查询。主要供后台管理使用，Service 层负责权限校验。
     */
    List<User> findByRole(UserRole role);

    /**
     * 保存用户。id 为空时视为新增，否则视为更新。
     */
    User save(User user);
}
