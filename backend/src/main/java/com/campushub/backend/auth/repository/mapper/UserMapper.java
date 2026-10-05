package com.campushub.backend.auth.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campushub.backend.auth.repository.entity.UserEntity;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * sys_user 表的 MyBatis-Plus Mapper。
 *
 * <p>仅提供数据访问能力，业务规则与权限判断仍在服务层完成。</p>
 *
 * <p>余额与冻结金额的变更通过 {@code @Update} 注解的原子 SQL 完成，
 * 让数据库行锁保证并发安全，避免「读-修改-写」模式下的 lost update。</p>
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    /**
     * 原子冻结余额：{@code frozen_balance = frozen_balance + amount}，
     * 仅当 {@code balance - frozen_balance >= amount} 时更新生效。
     *
     * @return 受影响行数，1 表示成功，0 表示余额不足或用户不存在
     */
    @Update("UPDATE sys_user SET frozen_balance = frozen_balance + #{amount}, "
        + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} "
        + "AND (balance - frozen_balance) >= #{amount}")
    int freezeBalance(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /**
     * 原子解冻金额：{@code frozen_balance = frozen_balance - amount}，
     * 仅当 {@code frozen_balance >= amount} 时更新生效。
     *
     * @return 受影响行数，1 表示成功，0 表示冻结金额不足或用户不存在
     */
    @Update("UPDATE sys_user SET frozen_balance = frozen_balance - #{amount}, "
        + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} "
        + "AND frozen_balance >= #{amount}")
    int unfreezeBalance(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /**
     * 原子增加余额：{@code balance = balance + amount}。
     *
     * @return 受影响行数，1 表示成功，0 表示用户不存在
     */
    @Update("UPDATE sys_user SET balance = balance + #{amount}, "
        + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int addBalance(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /**
     * 原子扣减余额：{@code balance = balance - amount}，
     * 仅当 {@code balance >= amount} 时更新生效。
     *
     * @return 受影响行数，1 表示成功，0 表示余额不足或用户不存在
     */
    @Update("UPDATE sys_user SET balance = balance - #{amount}, "
        + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} "
        + "AND balance >= #{amount}")
    int deductBalance(@Param("id") Long id, @Param("amount") BigDecimal amount);
}
