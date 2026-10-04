package com.campushub.backend.demand.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandSort;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.dto.DemandQuery;
import com.campushub.backend.demand.dto.DemandReviewQuery;
import com.campushub.backend.demand.repository.entity.DemandEntity;
import com.campushub.backend.demand.repository.mapper.DemandMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于 MyBatis-Plus 的 {@link DemandRepository} 实现。
 *
 * <p>默认仓储实现；实现严格遵循 {@code P4-数据库接口调用规范.md} 中对 DAO 层的契约：
 * 不在 DAO 层抛业务异常、查不到返回 {@link Optional#empty()}、
 * {@code findAll()} 返回空列表而非 null。</p>
 */
@Repository
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

    @Override
    public List<Demand> findPage(DemandQuery query) {
        if (query == null) {
            return List.of();
        }
        LambdaQueryWrapper<DemandEntity> wrapper = buildWrapper(query);
        applySort(wrapper, query.sort());
        int size = query.pageQuery().size();
        long offset = (long) (query.pageQuery().page() - 1) * size;
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        return demandMapper.selectList(wrapper).stream().map(DemandEntity::toDomain).toList();
    }

    @Override
    public long count(DemandQuery query) {
        if (query == null) {
            return 0L;
        }
        return demandMapper.selectCount(buildWrapper(query));
    }

    private LambdaQueryWrapper<DemandEntity> buildWrapper(DemandQuery query) {
        LocalDateTime now = LocalDateTime.now();
        Long currentUserId = query.currentUserId();
        LambdaQueryWrapper<DemandEntity> wrapper = new LambdaQueryWrapper<>();
        List<String> publicStatuses = List.of(
            DemandStatus.PENDING.name(),
            DemandStatus.IN_PROGRESS.name(),
            DemandStatus.COMPLETED.name());
        wrapper.and(w -> {
            w.and(v -> v.isNull(DemandEntity::getEndTime).or().ge(DemandEntity::getEndTime, now))
                .in(DemandEntity::getStatus, publicStatuses);
            if (currentUserId != null) {
                w.or(o -> o.eq(DemandEntity::getPublisherId, currentUserId));
            }
        });
        String q = query.q();
        if (q != null && !q.isBlank()) {
            String keyword = q.trim();
            wrapper.and(w -> w.like(DemandEntity::getTitle, keyword).or().like(DemandEntity::getDescription, keyword));
        }
        String category = query.category();
        if (category != null && !category.isBlank()) {
            wrapper.eq(DemandEntity::getCategory, category.trim().toUpperCase(Locale.ROOT));
        }
        String zone = query.campusZone();
        if (zone != null && !zone.isBlank()) {
            wrapper.eq(DemandEntity::getCampusZone, zone.trim().toUpperCase(Locale.ROOT));
        }
        String loc = query.location();
        if (loc != null && !loc.isBlank()) {
            wrapper.like(DemandEntity::getLocation, loc.trim());
        }
        LocalDateTime from = query.startTimeFrom();
        LocalDateTime to = query.startTimeTo();
        if (from != null || to != null) {
            wrapper.isNotNull(DemandEntity::getStartTime);
            if (from != null) {
                wrapper.ge(DemandEntity::getStartTime, from);
            }
            if (to != null) {
                wrapper.le(DemandEntity::getStartTime, to);
            }
        }
        return wrapper;
    }

    private void applySort(LambdaQueryWrapper<DemandEntity> wrapper, DemandSort sort) {
        DemandSort resolved = sort == null ? DemandSort.TIME : sort;
        switch (resolved) {
            case REWARD -> wrapper.orderByDesc(DemandEntity::getReward)
                                  .orderByDesc(DemandEntity::getCreatedAt)
                                  .orderByDesc(DemandEntity::getId);
            case TIME, DISTANCE, RECOMMEND -> wrapper.orderByDesc(DemandEntity::getCreatedAt)
                                                     .orderByDesc(DemandEntity::getId);
        }
    }

    @Override
    public List<Demand> findReviewPage(DemandReviewQuery query) {
        if (query == null) {
            return List.of();
        }
        LambdaQueryWrapper<DemandEntity> wrapper = buildReviewWrapper(query);
        wrapper.orderByDesc(DemandEntity::getCreatedAt)
               .orderByDesc(DemandEntity::getId);
        int size = query.pageQuery().size();
        long offset = (long) (query.pageQuery().page() - 1) * size;
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        return demandMapper.selectList(wrapper).stream().map(DemandEntity::toDomain).toList();
    }

    @Override
    public long countReview(DemandReviewQuery query) {
        if (query == null) {
            return 0L;
        }
        return demandMapper.selectCount(buildReviewWrapper(query));
    }

    private LambdaQueryWrapper<DemandEntity> buildReviewWrapper(DemandReviewQuery query) {
        LambdaQueryWrapper<DemandEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DemandEntity::getStatus, DemandStatus.REVIEWING.name());
        String q = query.q();
        if (q != null && !q.isBlank()) {
            String keyword = q.trim();
            wrapper.and(w -> w.like(DemandEntity::getTitle, keyword)
                .or().like(DemandEntity::getDescription, keyword)
                .or().like(DemandEntity::getLocation, keyword));
        }
        String category = query.category();
        if (category != null && !category.isBlank()) {
            wrapper.eq(DemandEntity::getCategory, category.trim().toUpperCase(Locale.ROOT));
        }
        String zone = query.campusZone();
        if (zone != null && !zone.isBlank()) {
            wrapper.eq(DemandEntity::getCampusZone, zone.trim().toUpperCase(Locale.ROOT));
        }
        return wrapper;
    }

    @Override
    public long countAll() {
        return demandMapper.selectCount(null);
    }

    @Override
    public long countByStatus(DemandStatus status) {
        if (status == null) {
            return 0L;
        }
        return demandMapper.selectCount(new LambdaQueryWrapper<DemandEntity>()
            .eq(DemandEntity::getStatus, status.name()));
    }

    @Override
    public List<Demand> findCandidatePage(Long userId, DemandQuery query) {
        if (query == null) {
            return List.of();
        }
        LambdaQueryWrapper<DemandEntity> wrapper = buildCandidateWrapper(userId, query);
        return demandMapper.selectList(wrapper).stream().map(DemandEntity::toDomain).toList();
    }

    @Override
    public Set<Long> findActivePublisherIdsByDate(LocalDate today) {
        if (today == null) {
            return Set.of();
        }
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        List<DemandEntity> entities = demandMapper.selectList(
            new LambdaQueryWrapper<DemandEntity>()
                .select(DemandEntity::getPublisherId)
                .and(w -> w.ge(DemandEntity::getCreatedAt, start).lt(DemandEntity::getCreatedAt, end)
                    .or().ge(DemandEntity::getUpdatedAt, start).lt(DemandEntity::getUpdatedAt, end)));
        return entities.stream().map(DemandEntity::getPublisherId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private LambdaQueryWrapper<DemandEntity> buildCandidateWrapper(Long userId, DemandQuery query) {
        LambdaQueryWrapper<DemandEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DemandEntity::getStatus, DemandStatus.PENDING.name());
        if (userId != null) {
            wrapper.ne(DemandEntity::getPublisherId, userId);
        }
        String q = query.q();
        if (q != null && !q.isBlank()) {
            String keyword = q.trim();
            wrapper.and(w -> w.like(DemandEntity::getTitle, keyword).or().like(DemandEntity::getDescription, keyword));
        }
        String category = query.category();
        if (category != null && !category.isBlank()) {
            wrapper.eq(DemandEntity::getCategory, category.trim().toUpperCase(Locale.ROOT));
        }
        String zone = query.campusZone();
        if (zone != null && !zone.isBlank()) {
            wrapper.eq(DemandEntity::getCampusZone, zone.trim().toUpperCase(Locale.ROOT));
        }
        String loc = query.location();
        if (loc != null && !loc.isBlank()) {
            wrapper.like(DemandEntity::getLocation, loc.trim());
        }
        LocalDateTime from = query.startTimeFrom();
        LocalDateTime to = query.startTimeTo();
        if (from != null || to != null) {
            wrapper.isNotNull(DemandEntity::getStartTime);
            if (from != null) {
                wrapper.ge(DemandEntity::getStartTime, from);
            }
            if (to != null) {
                wrapper.le(DemandEntity::getStartTime, to);
            }
        }
        return wrapper;
    }
}
