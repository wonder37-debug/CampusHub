package com.campushub.backend.demand.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campushub.backend.demand.domain.DemandResponse;
import com.campushub.backend.demand.domain.ResponseStatus;
import com.campushub.backend.demand.repository.entity.DemandResponseEntity;
import com.campushub.backend.demand.repository.mapper.DemandResponseMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisDemandResponseRepository implements DemandResponseRepository {

    private static final List<ResponseStatus> ACTIVE_STATUSES = List.of(ResponseStatus.PENDING, ResponseStatus.SELECTED);

    private final DemandResponseMapper demandResponseMapper;

    public MyBatisDemandResponseRepository(DemandResponseMapper demandResponseMapper) {
        this.demandResponseMapper = demandResponseMapper;
    }

    @Override
    public DemandResponse save(DemandResponse response) {
        DemandResponseEntity entity = DemandResponseEntity.fromDomain(response);
        LocalDateTime now = LocalDateTime.now();
        if (entity.getId() == null) {
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            try {
                demandResponseMapper.insert(entity);
            } catch (DuplicateKeyException ignored) {
                // uk_response_demand_author_active 冲突：同 demand+author+status 已存在
                throw new DuplicateKeyException("response already exists for this demand/author/status");
            }
        } else {
            entity.setUpdatedAt(now);
            demandResponseMapper.updateById(entity);
        }
        response.setId(entity.getId());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }

    @Override
    public Optional<DemandResponse> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        DemandResponseEntity entity = demandResponseMapper.selectById(id);
        return Optional.ofNullable(entity).map(DemandResponseEntity::toDomain);
    }

    @Override
    public List<DemandResponse> findByDemandId(Long demandId) {
        if (demandId == null) {
            return List.of();
        }
        return demandResponseMapper.selectList(new LambdaQueryWrapper<DemandResponseEntity>()
                .eq(DemandResponseEntity::getDemandId, demandId)
                .orderByAsc(DemandResponseEntity::getCreatedAt))
            .stream()
            .map(DemandResponseEntity::toDomain)
            .toList();
    }

    @Override
    public List<DemandResponse> findByDemandIdAndStatusIn(Long demandId, Collection<ResponseStatus> statuses) {
        if (demandId == null || statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        List<String> statusNames = statuses.stream().map(Enum::name).toList();
        return demandResponseMapper.selectList(new LambdaQueryWrapper<DemandResponseEntity>()
                .eq(DemandResponseEntity::getDemandId, demandId)
                .in(DemandResponseEntity::getStatus, statusNames)
                .orderByAsc(DemandResponseEntity::getCreatedAt))
            .stream()
            .map(DemandResponseEntity::toDomain)
            .toList();
    }

    @Override
    public Optional<DemandResponse> findActiveByDemandIdAndAuthorId(Long demandId, Long authorId) {
        if (demandId == null || authorId == null) {
            return Optional.empty();
        }
        List<String> statusNames = ACTIVE_STATUSES.stream().map(Enum::name).toList();
        DemandResponseEntity entity = demandResponseMapper.selectOne(new LambdaQueryWrapper<DemandResponseEntity>()
                .eq(DemandResponseEntity::getDemandId, demandId)
                .eq(DemandResponseEntity::getAuthorId, authorId)
                .in(DemandResponseEntity::getStatus, statusNames)
                .last("LIMIT 1"));
        return Optional.ofNullable(entity).map(DemandResponseEntity::toDomain);
    }

    @Override
    public List<DemandResponse> findSelectedByDemandId(Long demandId) {
        if (demandId == null) {
            return List.of();
        }
        return demandResponseMapper.selectList(new LambdaQueryWrapper<DemandResponseEntity>()
                .eq(DemandResponseEntity::getDemandId, demandId)
                .eq(DemandResponseEntity::getStatus, ResponseStatus.SELECTED.name())
                .orderByAsc(DemandResponseEntity::getCreatedAt))
            .stream()
            .map(DemandResponseEntity::toDomain)
            .toList();
    }

    @Override
    public long countSelectedByDemandId(Long demandId) {
        if (demandId == null) {
            return 0L;
        }
        return demandResponseMapper.selectCount(new LambdaQueryWrapper<DemandResponseEntity>()
                .eq(DemandResponseEntity::getDemandId, demandId)
                .eq(DemandResponseEntity::getStatus, ResponseStatus.SELECTED.name()));
    }

    @Override
    public List<DemandResponse> findByIdIn(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        return demandResponseMapper.selectList(new LambdaQueryWrapper<DemandResponseEntity>()
                .in(DemandResponseEntity::getId, ids))
            .stream()
            .map(DemandResponseEntity::toDomain)
            .toList();
    }
}
