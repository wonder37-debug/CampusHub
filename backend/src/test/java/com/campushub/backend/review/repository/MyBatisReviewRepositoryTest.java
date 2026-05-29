package com.campushub.backend.review.repository;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.campushub.backend.review.domain.Review;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MyBatisReviewRepository} 的切片测试。
 *
 * <p>使用 H2 内存库，通过 {@code @Sql} 单独加载 ord_review 建表脚本，
 * 严格覆盖 INSERT / UPDATE / 多条件查询 / 防重评价底线等关键路径。</p>
 */
@MybatisPlusTest
@ActiveProfiles("local")
@Import(MyBatisReviewRepository.class)
@Sql(scripts = "classpath:schema-review.sql")
class MyBatisReviewRepositoryTest {

    @Autowired
    private MyBatisReviewRepository repository;

    @Test
    void save_insert_assigns_id_and_findByOrderIdAndAuthorId_returns_review() {
        Review review = newReview(1001L, 10L, 20L, 5);

        Review saved = repository.save(review);

        assertThat(saved.getId()).isNotNull();
        Optional<Review> found = repository.findByOrderIdAndAuthorId(1001L, 10L);
        assertThat(found).isPresent();
        assertThat(found.get().getTargetId()).isEqualTo(20L);
        assertThat(found.get().getRating()).isEqualTo(5);
        assertThat(found.get().getComment()).isEqualTo("好评");
    }

    @Test
    void save_update_when_id_present_changes_rating_and_comment() {
        Review review = repository.save(newReview(1002L, 10L, 20L, 4));

        review.setRating(1);
        review.setComment("差评修改");
        repository.save(review);

        Review reloaded = repository.findByOrderIdAndAuthorId(1002L, 10L).orElseThrow();
        assertThat(reloaded.getRating()).isEqualTo(1);
        assertThat(reloaded.getComment()).isEqualTo("差评修改");
        // UPDATE 不新增记录
        assertThat(repository.findByOrderId(1002L)).hasSize(1);
    }

    @Test
    void findByOrderIdAndAuthorId_returns_empty_when_not_found_or_null() {
        assertThat(repository.findByOrderIdAndAuthorId(9999L, 10L)).isEmpty();
        assertThat(repository.findByOrderIdAndAuthorId(null, 10L)).isEmpty();
        assertThat(repository.findByOrderIdAndAuthorId(1001L, null)).isEmpty();
        assertThat(repository.findByOrderIdAndAuthorId(null, null)).isEmpty();
    }

    @Test
    void findByTargetId_returns_all_reviews_for_a_target() {
        repository.save(newReview(2001L, 10L, 30L, 5)); // targetId=30
        repository.save(newReview(2002L, 11L, 30L, 3)); // targetId=30
        repository.save(newReview(2003L, 12L, 40L, 4)); // targetId=40（无关）

        List<Review> forTarget30 = repository.findByTargetId(30L);
        assertThat(forTarget30).hasSize(2);
        assertThat(forTarget30).extracting(Review::getOrderId)
            .containsExactlyInAnyOrder(2001L, 2002L);

        assertThat(repository.findByTargetId(null)).isNotNull().isEmpty();
    }

    @Test
    void findByOrderId_returns_all_reviews_for_an_order() {
        repository.save(newReview(3001L, 10L, 20L, 5)); // orderId=3001, author=10
        repository.save(newReview(3001L, 20L, 10L, 4)); // orderId=3001, author=20（互相评价）
        repository.save(newReview(3002L, 11L, 21L, 3)); // orderId=3002（无关）

        List<Review> forOrder3001 = repository.findByOrderId(3001L);
        assertThat(forOrder3001).hasSize(2);
        assertThat(forOrder3001).extracting(Review::getAuthorId)
            .containsExactlyInAnyOrder(10L, 20L);

        assertThat(repository.findByOrderId(null)).isNotNull().isEmpty();
    }

    @Test
    void findByTargetId_returns_empty_list_not_null_when_no_reviews() {
        List<Review> results = repository.findByTargetId(999L);
        assertThat(results).isNotNull().isEmpty();
    }

    @Test
    void findByOrderId_returns_empty_list_not_null_when_no_reviews() {
        List<Review> results = repository.findByOrderId(999L);
        assertThat(results).isNotNull().isEmpty();
    }

    /**
     * 防重评价底线：往同一 order_id + author_id 写入第二条评价，
     * 必须由 ord_review.uk_review_order_author 唯一索引拒绝，
     * 异常以 {@link DuplicateKeyException}/{@link DataIntegrityViolationException}
     * 形式向上抛出，绝不被仓储层吞掉。
     */
    @Test
    void duplicate_order_author_triggers_unique_constraint_violation() {
        Review first = repository.save(newReview(4001L, 10L, 20L, 5));
        assertThat(first.getId()).isNotNull();

        Review second = newReview(4001L, 10L, 30L, 1); // 同 orderId + authorId，不同 targetId
        assertThatThrownBy(() -> repository.save(second))
            .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class);
    }

    /**
     * 工厂方法：为所有 NOT NULL 列（order_id / author_id / target_id / rating / created_at）
     * 提供默认值，避免 H2 抛出 NULL not allowed 异常。
     */
    private static Review newReview(Long orderId, Long authorId, Long targetId, int rating) {
        Review review = new Review();
        review.setOrderId(orderId);
        review.setAuthorId(authorId);
        review.setTargetId(targetId);
        review.setRating(rating);
        review.setComment("好评");
        review.setCreatedAt(LocalDateTime.now());
        return review;
    }
}