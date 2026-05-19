package com.campushub.backend.review.repository;

import com.campushub.backend.review.domain.Review;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!local")
public class InMemoryReviewRepository implements ReviewRepository {

    private final AtomicLong sequence = new AtomicLong(1);
    private final List<Review> reviews = new CopyOnWriteArrayList<>();

    @Override
    public Review save(Review review) {
        if (review.getId() == null) {
            review.setId(sequence.getAndIncrement());
            reviews.add(review);
            return review;
        }
        return review;
    }

    @Override
    public Optional<Review> findByOrderIdAndAuthorId(Long orderId, Long authorId) {
        return reviews.stream()
            .filter(review -> review.getOrderId().equals(orderId) && review.getAuthorId().equals(authorId))
            .findFirst();
    }

    @Override
    public List<Review> findByTargetId(Long targetId) {
        return reviews.stream().filter(review -> review.getTargetId().equals(targetId)).toList();
    }

    @Override
    public List<Review> findByOrderId(Long orderId) {
        return new ArrayList<>(reviews.stream().filter(review -> review.getOrderId().equals(orderId)).toList());
    }
}
