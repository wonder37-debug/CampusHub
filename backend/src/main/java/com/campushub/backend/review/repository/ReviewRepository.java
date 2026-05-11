package com.campushub.backend.review.repository;

import com.campushub.backend.review.domain.Review;
import java.util.List;
import java.util.Optional;

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
     * 查询某个订单下的全部评价记录。
     */
    List<Review> findByOrderId(Long orderId);
}
