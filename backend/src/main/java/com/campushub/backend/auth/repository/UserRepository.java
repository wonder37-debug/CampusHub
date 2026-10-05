package com.campushub.backend.auth.repository;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.dto.UserQueryCriteria;
import java.math.BigDecimal;
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

    /**
     * 原子冻结余额：{@code frozen_balance += amount}，仅当 {@code balance - frozen_balance >= amount} 时生效。
     *
     * <p>通过数据库行锁保证并发安全，避免「读-修改-写」模式下的 lost update。
     * 调用方应在返回 {@code false} 时抛出业务异常以触发事务回滚。</p>
     *
     * @param userId 用户 ID，不能为空
     * @param amount 冻结金额，必须为正数
     * @return {@code true} 表示冻结成功；{@code false} 表示余额不足或用户不存在
     */
    boolean freezeBalance(Long userId, BigDecimal amount);

    /**
     * 原子解冻金额：{@code frozen_balance -= amount}，仅当 {@code frozen_balance >= amount} 时生效。
     *
     * @param userId 用户 ID，不能为空
     * @param amount 解冻金额，必须为正数
     * @return {@code true} 表示解冻成功；{@code false} 表示冻结金额不足或用户不存在
     */
    boolean unfreezeBalance(Long userId, BigDecimal amount);

    /**
     * 原子增加余额：{@code balance += amount}。
     *
     * @param userId 用户 ID，不能为空
     * @param amount 增加金额，必须为正数
     * @return {@code true} 表示成功；{@code false} 表示用户不存在
     */
    boolean addBalance(Long userId, BigDecimal amount);

    /**
     * 原子扣减余额：{@code balance -= amount}，仅当 {@code balance >= amount} 时生效。
     *
     * @param userId 用户 ID，不能为空
     * @param amount 扣减金额，必须为正数
     * @return {@code true} 表示扣减成功；{@code false} 表示余额不足或用户不存在
     */
    boolean deductBalance(Long userId, BigDecimal amount);
}
