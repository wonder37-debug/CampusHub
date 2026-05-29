package com.campushub.backend.demand.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.repository.entity.DemandEntity;
import com.campushub.backend.demand.repository.mapper.DemandMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于 MyBatis-Plus 的 {@link DemandRepository} 实现。
 *
 * <p>仅在 {@code local} profile 下激活，避免与默认的内存仓储冲突；
 * 实现严格遵循 {@code P4-数据库接口调用规范.md} 中对 DAO 层的契约：
 * 不在 DAO 层抛业务异常、查不到返回 {@link Optional#empty()}、
 * {@code findAll()} 返回空列表而非 null。</p>
 */
@Repository
@Profile("local")
public class MyBatisDemandRepository implements DemandRepository {

    private final DemandMapper demandMapper;

    public MyBatisDemandRepository(DemandMapper demandMapper) {
        this.demandMapper = demandMapper;
    }

    @Override
    public Demand save(Demand demand) {
        if (demand == null) {
            throw new IllegalArgumentException("demand must not be null");
        }
        DemandEntity entity = DemandEntity.fromDomain(demand);
        if (demand.getId() == null) {
            demandMapper.insert(entity);
            demand.setId(entity.getId());
        } else {
            demandMapper.updateById(entity);
        }
        return demand;
    }

    @Override
    public Optional<Demand> findById(Long demandId) {
        if (demandId == null) {
            return Optional.empty();
        }
        DemandEntity entity = demandMapper.selectById(demandId);
        return Optional.ofNullable(entity).map(DemandEntity::toDomain);
    }

    @Override
    public List<Demand> findAll() {
        List<DemandEntity> entities = demandMapper.selectList(null);
        return entities.stream().map(DemandEntity::toDomain).toList();
    }

    @Override
    public List<Demand> findByStatus(DemandStatus status) {
        if (status == null) {
            return new ArrayList<>();
        }
        List<DemandEntity> entities = demandMapper.selectList(
            new LambdaQueryWrapper<DemandEntity>()
                .eq(DemandEntity::getStatus, status.name())
        );
        return entities.stream().map(DemandEntity::toDomain).toList();
    }
}
