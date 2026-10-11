package com.campushub.backend.order.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campushub.backend.order.domain.ArbitrationSubmission;
import com.campushub.backend.order.repository.entity.ArbitrationSubmissionEntity;
import com.campushub.backend.order.repository.mapper.ArbitrationSubmissionMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MyBatisArbitrationSubmissionRepository implements ArbitrationSubmissionRepository {

    private final ArbitrationSubmissionMapper mapper;

    public MyBatisArbitrationSubmissionRepository(ArbitrationSubmissionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ArbitrationSubmission save(ArbitrationSubmission submission) {
        ArbitrationSubmissionEntity entity = ArbitrationSubmissionEntity.fromDomain(submission);
        if (entity.getId() == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return entity.toDomain();
    }

    @Override
    public List<ArbitrationSubmission> findByOrderId(Long orderId) {
        List<ArbitrationSubmissionEntity> entities = mapper.selectList(
            new LambdaQueryWrapper<ArbitrationSubmissionEntity>()
                .eq(ArbitrationSubmissionEntity::getOrderId, orderId)
                .orderByAsc(ArbitrationSubmissionEntity::getSubmittedAt)
                .orderByAsc(ArbitrationSubmissionEntity::getId)
        );
        return entities.stream().map(ArbitrationSubmissionEntity::toDomain).toList();
    }

    @Override
    public List<ArbitrationSubmission> findByOrderIdAndRole(Long orderId, String role) {
        List<ArbitrationSubmissionEntity> entities = mapper.selectList(
            new LambdaQueryWrapper<ArbitrationSubmissionEntity>()
                .eq(ArbitrationSubmissionEntity::getOrderId, orderId)
                .eq(ArbitrationSubmissionEntity::getRole, role)
                .orderByAsc(ArbitrationSubmissionEntity::getSubmittedAt)
                .orderByAsc(ArbitrationSubmissionEntity::getId)
        );
        return entities.stream().map(ArbitrationSubmissionEntity::toDomain).toList();
    }

    @Override
    public int countByOrderId(Long orderId) {
        return Math.toIntExact(mapper.selectCount(
            new LambdaQueryWrapper<ArbitrationSubmissionEntity>()
                .eq(ArbitrationSubmissionEntity::getOrderId, orderId)
        ));
    }
}
