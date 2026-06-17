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
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.review.domain.Review;
import com.campushub.backend.review.dto.ReviewResponse;
import com.campushub.backend.review.repository.ReviewRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ApiViewMapper {

    private final DemandRepository demandRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;

    public ApiViewMapper(
        DemandRepository demandRepository,
        UserRepository userRepository,
        OrderRepository orderRepository,
        ReviewRepository reviewRepository
    ) {
        this.demandRepository = demandRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
    }

    public DemandView toDemandView(Demand demand, CurrentUser currentUser) {
        boolean canSeePublisher = canSeeDemandPublisher(demand, currentUser);
        User publisherUser = demand.getPublisherId() == null ? null : userRepository.findById(demand.getPublisherId()).orElse(null);
        Long publisherId = canSeePublisher ? demand.getPublisherId() : null;
        String publisherDisplayName = canSeePublisher ? demand.getPublisherDisplayName() : demand.getAnonymousCode();
        UserSummaryView publisher = publisherUser == null
            ? null
            : canSeePublisher
                ? UserSummaryView.from(publisherUser)
                : new UserSummaryView(
                    null,
                    null,
                    null,
                    demand.getAnonymousCode() != null ? demand.getAnonymousCode() : "匿名校友",
                    publisherUser.getAvatarUrl(),
                    publisherUser.getRole().name(),
                    publisherUser.getStatus().name(),
                    publisherUser.getCreditScore(),
                    null,
                    null
                );

        boolean publisherIdentityVisible = publisherUser != null
            && (!demand.isAnonymous()
            || currentUser != null && (currentUser.isAdmin() || demand.getPublisherId().equals(currentUser.userId())));
        String publisherStudentIdMasked = publisherUser == null
            ? null
            : resolvePublisherStudentIdMasked(publisherUser, publisherIdentityVisible);

        Order relatedOrder = demand.getId() == null ? null : orderRepository.findByDemandId(demand.getId()).orElse(null);
        boolean canAccept = canAcceptDemand(demand, relatedOrder, currentUser);

        return new DemandView(
            demand.getId(),
            publisherId,
            publisherDisplayName,
            publisher,
            publisherStudentIdMasked,
            publisherIdentityVisible,
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
            canAccept,
            canAccept ? null : resolveAcceptDisabledReason(demand, relatedOrder, currentUser),
            resolveAcceptStatusHint(demand, relatedOrder, currentUser),
            canStartExecution(relatedOrder, currentUser),
            canViewAcceptNote(relatedOrder, currentUser),
            canSubmitAcceptNote(relatedOrder, currentUser),
            demand.getCreatedAt(),
            demand.getUpdatedAt()
        );
    }

    public OrderView toOrderView(Order order, CurrentUser currentUser) {
        Demand demand = demandRepository.findById(order.getDemandId()).orElse(null);
        User requester = userRepository.findById(order.getPublisherId()).orElse(null);
        User provider = userRepository.findById(order.getAccepterId()).orElse(null);
        boolean canSeePublisher = demand == null || canSeeDemandPublisher(demand, currentUser);
        String anonymousCode = demand != null ? demand.getAnonymousCode() : null;

        List<ReviewView> reviews = reviewRepository.findByOrderId(order.getId()).stream()
            .map(review -> toReviewView(review, canSeePublisher, order.getPublisherId(), anonymousCode))
            .toList();
        boolean currentUserReviewed = currentUser != null
            && reviewRepository.findByOrderIdAndAuthorId(order.getId(), currentUser.userId()).isPresent();

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
            requester == null ? null : anonymizeUserSummary(UserSummaryView.from(requester), canSeePublisher, anonymousCode),
            provider == null ? null : UserSummaryView.from(provider),
            order.getStatusHistory().stream().map(this::toTimelineView).toList(),
            reviews,
            currentUserReviewed,
            resolvePendingReviewTarget(order, currentUser, currentUserReviewed),
            resolveCompletionHint(order, currentUser)
        );
    }

    public ReviewView toReviewView(Review review) {
        return toReviewView(review, true, null, null);
    }

    public ReviewView toAnonymizedReviewView(ReviewResponse review, CurrentUser currentUser) {
        Order order = orderRepository.findById(review.orderId()).orElse(null);
        Demand demand = order != null ? demandRepository.findById(order.getDemandId()).orElse(null) : null;
        boolean canSeePublisher = demand == null || canSeeDemandPublisher(demand, currentUser);
        String anonymousCode = demand != null ? demand.getAnonymousCode() : null;
        Long publisherId = order != null ? order.getPublisherId() : null;

        User author = userRepository.findById(review.authorId()).orElse(null);
        User target = userRepository.findById(review.targetId()).orElse(null);

        UserSummaryView authorView = author == null ? null
            : anonymizeUserSummary(
                UserSummaryView.from(author),
                canSeePublisher || publisherId == null || !publisherId.equals(review.authorId()),
                anonymousCode
            );
        String targetName = target == null ? null
            : (!canSeePublisher && publisherId != null && publisherId.equals(review.targetId()))
                ? (anonymousCode != null ? anonymousCode : "匿名校友")
                : target.getNickname();

        return new ReviewView(
            review.id(),
            review.orderId(),
            review.rating(),
            review.comment(),
            review.targetId(),
            targetName,
            authorView,
            review.createdAt()
        );
    }

    private ReviewView toReviewView(Review review, boolean canSeePublisher, Long publisherId, String anonymousCode) {
        User author = userRepository.findById(review.getAuthorId()).orElse(null);
        User target = userRepository.findById(review.getTargetId()).orElse(null);
        UserSummaryView authorView = author == null ? null
            : anonymizeUserSummary(
                UserSummaryView.from(author),
                canSeePublisher || publisherId == null || !publisherId.equals(review.getAuthorId()),
                anonymousCode
            );
        String targetName = target == null ? null
            : (!canSeePublisher && publisherId != null && publisherId.equals(review.getTargetId()))
                ? (anonymousCode != null ? anonymousCode : "匿名校友")
                : target.getNickname();

        return new ReviewView(
            review.getId(),
            review.getOrderId(),
            review.getRating(),
            review.getComment(),
            review.getTargetId(),
            targetName,
            authorView,
            review.getCreatedAt()
        );
    }

    private boolean canSeeDemandPublisher(Demand demand, CurrentUser currentUser) {
        return !demand.isAnonymous()
            || currentUser != null && (currentUser.isAdmin() || demand.getPublisherId().equals(currentUser.userId()));
    }

    private UserSummaryView anonymizeUserSummary(UserSummaryView original, boolean canSee, String anonymousCode) {
        if (canSee || original == null) {
            return original;
        }
        return new UserSummaryView(
            original.id(),
            null,
            null,
            anonymousCode != null ? anonymousCode : "匿名校友",
            original.avatarUrl(),
            original.role(),
            original.status(),
            original.creditScore(),
            original.balance(),
            original.frozenBalance()
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

    private boolean canAcceptDemand(Demand demand, Order relatedOrder, CurrentUser currentUser) {
        return resolveAcceptDisabledReason(demand, relatedOrder, currentUser) == null;
    }

    private String resolveAcceptDisabledReason(Demand demand, Order relatedOrder, CurrentUser currentUser) {
        if (currentUser == null) {
            return "LOGIN_REQUIRED";
        }
        if (currentUser.isAdmin()) {
            return "ADMIN_FORBIDDEN";
        }
        if (demand.getPublisherId() != null && demand.getPublisherId().equals(currentUser.userId())) {
            return "OWN_DEMAND";
        }
        if (demand.getEndTime() != null && demand.getEndTime().isBefore(LocalDateTime.now())) {
            return "DEMAND_EXPIRED";
        }
        if (demand.getStatus() == null || demand.getStatus() != DemandStatus.PENDING) {
            return "DEMAND_NOT_PENDING";
        }
        if (relatedOrder != null) {
            return switch (relatedOrder.getStatus()) {
                case ACCEPTED, IN_PROGRESS -> "DEMAND_ALREADY_ACCEPTED";
                case IN_ARBITRATION -> "DEMAND_ORDER_IN_ARBITRATION";
                case COMPLETED, CANCELLED -> "DEMAND_ORDER_CLOSED";
            };
        }
        return null;
    }

    private String resolveAcceptStatusHint(Demand demand, Order relatedOrder, CurrentUser currentUser) {
        if (relatedOrder == null) {
            return null;
        }
        boolean isPublisher = currentUser != null && demand.getPublisherId() != null && demand.getPublisherId().equals(currentUser.userId());
        boolean isAccepter = currentUser != null && relatedOrder.getAccepterId() != null
            && relatedOrder.getAccepterId().equals(currentUser.userId());

        return switch (relatedOrder.getStatus()) {
            case ACCEPTED -> isPublisher
                ? "已有同学接单，请等待对方处理。"
                : isAccepter ? "你已接单，请按时完成任务。" : "该需求已被接单。";
            case IN_PROGRESS -> isPublisher
                ? "订单正在执行中，请与接单方保持沟通。"
                : isAccepter ? "订单正在执行中，请按时完成任务。" : "该订单正在执行中。";
            case IN_ARBITRATION -> "该订单正在管理员仲裁中。";
            case COMPLETED -> "该订单已完成。";
            case CANCELLED -> "该订单已取消。";
        };
    }

    private boolean canStartExecution(Order order, CurrentUser currentUser) {
        return order != null
            && currentUser != null
            && currentUser.userId().equals(order.getAccepterId())
            && order.getStatus() == OrderStatus.ACCEPTED;
    }

    private boolean canViewAcceptNote(Order order, CurrentUser currentUser) {
        return order != null
            && currentUser != null
            && (currentUser.isAdmin() || order.isParticipant(currentUser.userId()));
    }

    private boolean canSubmitAcceptNote(Order order, CurrentUser currentUser) {
        return order != null
            && currentUser != null
            && currentUser.userId().equals(order.getAccepterId())
            && order.getStatus() == OrderStatus.ACCEPTED;
    }

    private String resolvePublisherStudentIdMasked(User publisherUser, boolean identityVisible) {
        if (publisherUser == null || publisherUser.getStudentId() == null) {
            return null;
        }
        return identityVisible ? publisherUser.getStudentId() : maskStudentId(publisherUser.getStudentId());
    }

    private String maskStudentId(String studentId) {
        if (studentId == null || studentId.length() < 3) {
            return studentId;
        }
        int prefixLength = Math.min(3, studentId.length());
        int suffixLength = studentId.length() > 5 ? 2 : 1;
        return studentId.substring(0, prefixLength) + "***" + studentId.substring(studentId.length() - suffixLength);
    }

    private Long resolvePendingReviewTarget(Order order, CurrentUser currentUser, boolean currentUserReviewed) {
        if (order == null || currentUser == null || currentUserReviewed || order.getStatus() != OrderStatus.COMPLETED) {
            return null;
        }
        if (currentUser.userId().equals(order.getPublisherId())) {
            return order.getAccepterId();
        }
        if (currentUser.userId().equals(order.getAccepterId())) {
            return order.getPublisherId();
        }
        return null;
    }

    private String resolveCompletionHint(Order order, CurrentUser currentUser) {
        if (order == null || currentUser == null) {
            return null;
        }
        if (order.getStatus() == OrderStatus.COMPLETED) {
            return "双方已确认完成";
        }
        if (order.getStatus() == OrderStatus.IN_ARBITRATION) {
            return "订单仲裁中，请等待管理员处理。";
        }
        if (order.getStatus() != OrderStatus.IN_PROGRESS) {
            return null;
        }

        boolean publisherConfirmed = hasCompletionConfirmation(order, order.getPublisherId());
        boolean accepterConfirmed = hasCompletionConfirmation(order, order.getAccepterId());
        if (!publisherConfirmed && !accepterConfirmed) {
            return null;
        }
        if (currentUser.userId().equals(order.getPublisherId())) {
            if (publisherConfirmed && !accepterConfirmed) {
                return "已确认完成，等待接单方确认。";
            }
            if (!publisherConfirmed && accepterConfirmed) {
                return "接单方已确认完成，等待您确认。";
            }
        }
        if (currentUser.userId().equals(order.getAccepterId())) {
            if (accepterConfirmed && !publisherConfirmed) {
                return "已确认完成，等待发布者确认。";
            }
            if (!accepterConfirmed && publisherConfirmed) {
                return "发布者已确认完成，等待您确认。";
            }
        }
        return null;
    }

    private boolean hasCompletionConfirmation(Order order, Long userId) {
        return userId != null && order.getStatusHistory().stream()
            .anyMatch(entry -> entry.operatorId() != null
                && entry.operatorId().equals(userId)
                && entry.fromStatus() == OrderStatus.IN_PROGRESS
                && entry.toStatus() == OrderStatus.IN_PROGRESS
                && ("鎺ュ崟鏂圭‘璁ゅ畬鎴愶紝绛夊緟闇€姹傛柟纭".equals(entry.note())
                    || "闇€姹傛柟纭瀹屾垚锛岀瓑寰呮帴鍗曟柟纭".equals(entry.note())
                    || "已确认完成，等待对方确认。".equals(entry.note())));
    }
}
