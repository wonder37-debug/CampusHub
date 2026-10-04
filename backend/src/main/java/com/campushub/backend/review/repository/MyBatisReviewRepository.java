package com.campushub.backend.review.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campushub.backend.review.domain.Review;
import com.campushub.backend.review.dto.ReviewQuery;
import com.campushub.backend.review.repository.entity.ReviewEntity;
import com.campushub.backend.review.repository.mapper.ReviewMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于 MyBatis-Plus 的 {@link ReviewRepository} 实现。
 *
 * <p>默认仓储实现。</p>
 *
 * <p>并发防重底线：依赖 ord_review 上的唯一索引 {@code uk_review_order_author(order_id, author_id)}，
 * 同一订单同一作者重复评价将由数据库抛出 SQLException，Spring 体系转换为 {@link
 * org.springframework.dao.DuplicateKeyException}。本类不做任何捕获，原样向上传播。</p>
 *
 * <p>信用分的计算与更新由 Service 层通过拉取评价列表在内存中完成，
 * 本仓储仅保证基本的查询与持久化正确。</p>
 */
@Repository
public class MyBatisReviewRepository implements ReviewRepository {

    private final ReviewMapper reviewMapper;

    public MyBatisReviewRepository(ReviewMapper reviewMapper) {
        this.reviewMapper = reviewMapper;
    }

    @Override
    public Review save(Review review) {
        if (review == null) {
            throw new IllegalArgumentException("review must not be null");
        }
        ReviewEntity entity = ReviewEntity.fromDomain(review);
        if (review.getId() == null) {
            // 唯一索引 uk_review_order_author 触发的重复评价异常会在此抛出
            reviewMapper.insert(entity);
            review.setId(entity.getId());
        } else {
            reviewMapper.updateById(entity);
        }
        return review;
    }

    @Override
    public Optional<Review> findByOrderIdAndAuthorId(Long orderId, Long authorId) {
        if (orderId == null || authorId == null) {
            return Optional.empty();
        }
        ReviewEntity entity = reviewMapper.selectOne(
            new LambdaQueryWrapper<ReviewEntity>()
                .eq(ReviewEntity::getOrderId, orderId)
                .eq(ReviewEntity::getAuthorId, authorId)
        );
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(entity.toDomain());
    }

    @Override
    public List<Review> findByTargetId(Long targetId) {
        if (targetId == null) {
            return new ArrayList<>();
        }
        List<ReviewEntity> entities = reviewMapper.selectList(
            new LambdaQueryWrapper<ReviewEntity>()
                .eq(ReviewEntity::getTargetId, targetId)
        );
        return entities.stream().map(ReviewEntity::toDomain).toList();
    }

    @Override
    public List<Review> findByAuthorId(Long authorId) {
        if (authorId == null) {
            return new ArrayList<>();
        }
        List<ReviewEntity> entities = reviewMapper.selectList(
            new LambdaQueryWrapper<ReviewEntity>()
                .eq(ReviewEntity::getAuthorId, authorId)
        );
        return entities.stream().map(ReviewEntity::toDomain).toList();
    }

    @Override
    public List<Review> findByOrderId(Long orderId) {
        if (orderId == null) {
            return new ArrayList<>();
        }
        List<ReviewEntity> entities = reviewMapper.selectList(
            new LambdaQueryWrapper<ReviewEntity>()
                .eq(ReviewEntity::getOrderId, orderId)
        );
        return entities.stream().map(ReviewEntity::toDomain).toList();
    }

    @Override
    public List<Review> findPage(Long targetUserId, ReviewQuery query) {
        if (targetUserId == null || query == null) {
            return List.of();
        }
        LambdaQueryWrapper<ReviewEntity> wrapper = buildWrapper(targetUserId, query);
        wrapper.orderByDesc(ReviewEntity::getCreatedAt)
               .orderByDesc(ReviewEntity::getId);
        int size = query.pageQuery().size();
        long offset = (long) (query.pageQuery().page() - 1) * size;
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        return reviewMapper.selectList(wrapper).stream().map(ReviewEntity::toDomain).toList();
    }

    @Override
    public long count(Long targetUserId, ReviewQuery query) {
        if (targetUserId == null || query == null) {
            return 0L;
        }
        return reviewMapper.selectCount(buildWrapper(targetUserId, query));
    }

    @Override
    public Set<Long> findActiveAuthorIdsByDate(LocalDate today) {
        if (today == null) {
            return Set.of();
        }
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        List<ReviewEntity> entities = reviewMapper.selectList(
            new LambdaQueryWrapper<ReviewEntity>()
                .select(ReviewEntity::getAuthorId)
                .ge(ReviewEntity::getCreatedAt, start).lt(ReviewEntity::getCreatedAt, end));
        return entities.stream().map(ReviewEntity::getAuthorId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    @Override
    public Set<Long> findReviewedOrderIdsByAuthor(Long authorId, Collection<Long> orderIds) {
        if (authorId == null || orderIds == null || orderIds.isEmpty()) {
            return Set.of();
        }
        List<ReviewEntity> entities = reviewMapper.selectList(
            new LambdaQueryWrapper<ReviewEntity>()
                .select(ReviewEntity::getOrderId)
                .eq(ReviewEntity::getAuthorId, authorId)
                .in(ReviewEntity::getOrderId, orderIds));
        return entities.stream().map(ReviewEntity::getOrderId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private LambdaQueryWrapper<ReviewEntity> buildWrapper(Long targetUserId, ReviewQuery query) {
        LambdaQueryWrapper<ReviewEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(ReviewEntity::getTargetId, targetUserId)
            .or().eq(ReviewEntity::getAuthorId, targetUserId));
        return wrapper;
    }
}