package com.campushub.backend.review.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campushub.backend.review.domain.Review;
import com.campushub.backend.review.repository.entity.ReviewEntity;
import com.campushub.backend.review.repository.mapper.ReviewMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于 MyBatis-Plus 的 {@link ReviewRepository} 实现。
 *
 * <p>仅在 {@code local} profile 下激活，避免与默认内存仓储冲突。</p>
 *
 * <p>并发防重底线：依赖 ord_review 上的唯一索引 {@code uk_review_order_author(order_id, author_id)}，
 * 同一订单同一作者重复评价将由数据库抛出 SQLException，Spring 体系转换为 {@link
 * org.springframework.dao.DuplicateKeyException}。本类不做任何捕获，原样向上传播。</p>
 *
 * <p>信用分的计算与更新由 Service 层通过拉取评价列表在内存中完成，
 * 本仓储仅保证基本的查询与持久化正确。</p>
 */
@Repository
@Profile("local")
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
}