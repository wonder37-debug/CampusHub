package com.campushub.backend.auth.repository;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.dto.UserQueryCriteria;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository {

    /**
     * 按主键查询用户，不存在时返回空。
     */
    Optional<User> findById(Long id);

    /**
     * 按主键集合批量查询用户，供列表场景预加载使用。
     */
    List<User> findAllById(Collection<Long> ids);

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
     * 按查询条件分页查询用户（过滤 + 排序 + LIMIT/OFFSET 下推 SQL）。
     *
     * @param criteria 查询条件，为 null 时返回空列表
     */
    List<User> findPage(UserQueryCriteria criteria);

    /**
     * 按查询条件统计匹配的用户总数（过滤下推 SQL，用于分页 total）。
     *
     * @param criteria 查询条件，为 null 时返回 0
     */
    long count(UserQueryCriteria criteria);

    /**
     * 保存用户。id 为空时视为新增，否则视为更新。
     */
    User save(User user);

    /**
     * 统计用户总数（下推 SQL selectCount，用于 dashboard stats）。
     */
    long count();
}
