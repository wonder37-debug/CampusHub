package com.campushub.backend.review.service;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.notification.service.NotificationApplicationService;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.review.domain.Review;
import com.campushub.backend.review.dto.ReviewQuery;
import com.campushub.backend.review.dto.ReviewResponse;
import com.campushub.backend.review.dto.SubmitReviewCommand;
import com.campushub.backend.review.repository.ReviewRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ReviewApplicationServiceImpl implements ReviewApplicationService {

    private static final int DEFAULT_CREDIT_SCORE = 100;
    private static final double HISTORY_WEIGHT = 0.9;
    private static final double NEW_REVIEW_WEIGHT = 0.1;

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final NotificationApplicationService notificationApplicationService;

    public ReviewApplicationServiceImpl(
        ReviewRepository reviewRepository,
        OrderRepository orderRepository,
        UserRepository userRepository,
        NotificationApplicationService notificationApplicationService
    ) {
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.notificationApplicationService = notificationApplicationService;
    }

    @Override
    public ReviewResponse submit(Long operatorId, Long orderId, SubmitReviewCommand command) {
        if (command == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "submit review command must not be null");
        }
        if (command.rating() < 1 || command.rating() > 5) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "rating must be between 1 and 5");
        }
        if (command.comment() != null && command.comment().length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "comment length must not exceed 1000");
        }

        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "order not found"));
        if (!order.isParticipant(operatorId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "only participants can review this order");
        }
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "only completed orders can be reviewed");
        }
        if (reviewRepository.findByOrderIdAndAuthorId(orderId, operatorId).isPresent()) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "review already submitted for this order");
        }

        Long targetId = order.getPublisherId().equals(operatorId) ? order.getAccepterId() : order.getPublisherId();
        Review review = new Review(
            null,
            orderId,
            operatorId,
            targetId,
            command.rating(),
            trimToNull(command.comment()),
            LocalDateTime.now()
        );
        review = reviewRepository.save(review);
        recalculateCreditScore(targetId);
        notificationApplicationService.notifyReviewReceived(targetId, orderId);
        return ReviewResponse.from(review);
    }

    @Override
    public PageResponse<ReviewResponse> listUserReviews(Long targetUserId, ReviewQuery query) {
        if (targetUserId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "targetUserId must not be null");
        }
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "review query must not be null");
        }
        List<Review> received = reviewRepository.findByTargetId(targetUserId);
        List<Review> given = reviewRepository.findByAuthorId(targetUserId);
        List<Review> reviews = java.util.stream.Stream.concat(received.stream(), given.stream())
            .sorted(Comparator.comparing(Review::getCreatedAt).reversed())
            .toList();
        int page = query.pageQuery().page();
        int size = query.pageQuery().size();
        int fromIndex = Math.max(0, (page - 1) * size);
        int toIndex = Math.min(reviews.size(), fromIndex + size);
        List<ReviewResponse> items = fromIndex >= reviews.size()
            ? List.of()
            : reviews.subList(fromIndex, toIndex).stream().map(ReviewResponse::from).toList();
        return new PageResponse<>(items, page, size, reviews.size());
    }

    @Override
    public int recalculateCreditScore(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "user not found"));

        List<Review> reviews = reviewRepository.findByTargetId(userId).stream()
            .sorted(Comparator.comparing(Review::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo)))
            .toList();

        int newScore = DEFAULT_CREDIT_SCORE;
        for (Review review : reviews) {
            int normalizedRating = normalizeRating(review.getRating());
            newScore = clampCreditScore((int) Math.round(newScore * HISTORY_WEIGHT + normalizedRating * NEW_REVIEW_WEIGHT));
        }

        user.setCreditScore(newScore);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return newScore;
    }

    private int normalizeRating(int rating) {
        return rating * 20;
    }

    private int clampCreditScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
