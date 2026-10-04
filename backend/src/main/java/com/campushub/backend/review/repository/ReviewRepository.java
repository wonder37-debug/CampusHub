package com.campushub.backend.review.repository;

import com.campushub.backend.review.domain.Review;
import com.campushub.backend.review.dto.ReviewQuery;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ReviewRepository {

    /**
     * 保存评价。id 为空时视为新增，否则视为更新。
     */
    Review save(Review review);

    /**
     * 按订单与评价作者查询评价。数据库实现需保证同一订单同一作者最多一条记录。
     */
    Optional<Review> findByOrderIdAndAuthorId(Long orderId, Long authorId);

    /**
     * 查询某个被评价用户收到的全部评价。
     */
    List<Review> findByTargetId(Long targetId);

    /**
     * 查询某个用户发出的全部评价。
     */
    List<Review> findByAuthorId(Long authorId);

    /**
     * 查询某个订单下的全部评价记录。
     */
    List<Review> findByOrderId(Long orderId);

    /**
     * 按订单 ID 集合批量查询评价（用于列表场景预加载，避免逐条 findByOrderId N+1）。
     */
    List<Review> findAllByOrderIdIn(Collection<Long> orderIds);

    /**
     * 按用户与查询条件分页查询评价（target_id 或 author_id 命中 + 排序 + LIMIT/OFFSET 下推 SQL）。
     *
     * @param targetUserId 用户 ID，为 null 时返回空列表
     * @param query 查询条件，为 null 时返回空列表
     */
    List<Review> findPage(Long targetUserId, ReviewQuery query);

    /**
     * 按用户与查询条件统计匹配的评价总数（过滤下推 SQL，用于分页 total）。
     *
     * @param targetUserId 用户 ID，为 null 时返回 0
     * @param query 查询条件，为 null 时返回 0
     */
    long count(Long targetUserId, ReviewQuery query);

    Set<Long> findActiveAuthorIdsByDate(LocalDate today);

    /**
     * 查询给定 order 集合中已被指定作者评价的 order_id（用于批量排除已评价订单）。
     */
    Set<Long> findReviewedOrderIdsByAuthor(Long authorId, Collection<Long> orderIds);
}
