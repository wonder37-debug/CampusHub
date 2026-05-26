package com.campushub.backend.api;

import com.campushub.backend.api.view.DemandView;
import com.campushub.backend.api.view.OrderTimelineView;
import com.campushub.backend.api.view.OrderView;
import com.campushub.backend.api.view.ReviewView;
import com.campushub.backend.api.view.UserSummaryView;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;
import com.campushub.backend.review.domain.Review;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ApiViewMapper {

    private final DemandRepository demandRepository;
    private final UserRepository userRepository;

    public ApiViewMapper(DemandRepository demandRepository, UserRepository userRepository) {
        this.demandRepository = demandRepository;
        this.userRepository = userRepository;
    }

    public DemandView toDemandView(Demand demand, CurrentUser currentUser) {
        boolean canSeePublisher = !demand.isAnonymous()
            || currentUser != null && (currentUser.isAdmin() || demand.getPublisherId().equals(currentUser.userId()));
        Long publisherId = canSeePublisher ? demand.getPublisherId() : null;
        String publisherDisplayName = canSeePublisher ? demand.getPublisherDisplayName() : demand.getAnonymousCode();
        UserSummaryView publisher = canSeePublisher && demand.getPublisherId() != null
            ? userRepository.findById(demand.getPublisherId()).map(UserSummaryView::from).orElse(null)
            : null;

        return new DemandView(
            demand.getId(),
            publisherId,
            publisherDisplayName,
            publisher,
            demand.getTitle(),
            demand.getDescription(),
            demand.getCategory().name(),
            demand.getCampusZone().name(),
            demand.getLocation(),
            demand.getStartTime(),
            demand.getEndTime(),
            demand.getReward(),
            demand.getTags(),
            demand.getStatus().name(),
            demand.isAnonymous(),
            demand.getAnonymousCode(),
            demand.getCreatedAt(),
            demand.getUpdatedAt()
        );
    }

    public OrderView toOrderView(Order order, CurrentUser currentUser) {
        Demand demand = demandRepository.findById(order.getDemandId()).orElse(null);
        User requester = userRepository.findById(order.getPublisherId()).orElse(null);
        User provider = userRepository.findById(order.getAccepterId()).orElse(null);

        return new OrderView(
            order.getId(),
            order.getId(),
            order.getStatus().name(),
            order.getDemandId(),
            order.getPublisherId(),
            order.getAccepterId(),
            order.getAcceptNote(),
            order.isProofSubmitted(),
            order.getProofImageCount(),
            order.getCreatedAt(),
            order.getUpdatedAt(),
            order.getCompletedAt(),
            demand == null ? null : toDemandView(demand, currentUser),
            requester == null ? null : UserSummaryView.from(requester),
            provider == null ? null : UserSummaryView.from(provider),
            order.getStatusHistory().stream().map(this::toTimelineView).toList()
        );
    }

    public ReviewView toReviewView(Review review) {
        User author = userRepository.findById(review.getAuthorId()).orElse(null);
        User target = userRepository.findById(review.getTargetId()).orElse(null);
        return new ReviewView(
            review.getId(),
            review.getOrderId(),
            review.getRating(),
            review.getComment(),
            review.getTargetId(),
            target == null ? null : target.getNickname(),
            author == null ? null : UserSummaryView.from(author),
            review.getCreatedAt()
        );
    }

    private OrderTimelineView toTimelineView(OrderStatusHistoryEntry entry) {
        return new OrderTimelineView(
            entry.changedAt(),
            entry.fromStatus() == null ? null : entry.fromStatus().name(),
            entry.toStatus().name(),
            entry.operatorId(),
            entry.note()
        );
    }
}
