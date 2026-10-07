package com.campushub.backend.api;

import com.campushub.backend.api.view.DemandView;
import com.campushub.backend.api.view.OrderTimelineView;
import com.campushub.backend.api.view.OrderView;
import com.campushub.backend.api.view.ReviewView;
import com.campushub.backend.api.view.PublicUserSummaryView;
import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.security.CurrentUser;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.domain.InteractionMode;
import com.campushub.backend.demand.repository.DemandRepository;
import com.campushub.backend.demand.repository.DemandResponseRepository;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;
import com.campushub.backend.order.repository.OrderRepository;
import com.campushub.backend.review.domain.Review;
import com.campushub.backend.review.dto.ReviewResponse;
import com.campushub.backend.review.repository.ReviewRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ApiViewMapper {

    private final DemandRepository demandRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;
    private final DemandResponseRepository demandResponseRepository;

    public ApiViewMapper(
        DemandRepository demandRepository,
        UserRepository userRepository,
        OrderRepository orderRepository,
        ReviewRepository reviewRepository,
        DemandResponseRepository demandResponseRepository
    ) {
        this.demandRepository = demandRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
        this.demandResponseRepository = demandResponseRepository;
    }

    public DemandView toDemandView(Demand demand, CurrentUser currentUser) {
        return toDemandView(demand, currentUser, null, null);
    }

    public DemandView toDemandView(Demand demand, CurrentUser currentUser, Map<Long, User> userMap, Map<Long, Order> orderMap) {
        boolean canSeePublisher = canSeeDemandPublisher(demand, currentUser);
        User publisherUser = demand.getPublisherId() == null ? null : resolveUser(demand.getPublisherId(), userMap);
        Long publisherId = canSeePublisher ? demand.getPublisherId() : null;
        String publisherDisplayName = canSeePublisher ? demand.getPublisherDisplayName() : demand.getAnonymousCode();
        PublicUserSummaryView publisher = publisherUser == null
            ? null
            : canSeePublisher
                ? PublicUserSummaryView.from(publisherUser)
                : new PublicUserSummaryView(
                    null,
                    demand.getAnonymousCode() != null ? demand.getAnonymousCode() : "匿名校友",
                    publisherUser.getAvatarUrl(),
                    publisherUser.getRole().name(),
                    publisherUser.getStatus().name(),
                    publisherUser.getCreditScore()
                );

        boolean publisherIdentityVisible = publisherUser != null
            && (!demand.isAnonymous()
            || currentUser != null && (currentUser.isAdmin() || demand.getPublisherId().equals(currentUser.userId())));
        String publisherStudentIdMasked = publisherUser == null
            ? null
            : resolvePublisherStudentIdMasked(publisherUser, publisherIdentityVisible);

        Order relatedOrder = demand.getId() == null ? null : resolveOrder(demand.getId(), orderMap);
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
            demand.getInteractionMode() == null ? "DIRECT_ACCEPT" : demand.getInteractionMode().name(),
            demand.getTargetParticipantCount(),
            resolveSelectedParticipantCount(demand),
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
            demand.getImages(),
            resolveContactInfo(demand, relatedOrder, currentUser),
            demand.getCreatedAt(),
            demand.getUpdatedAt()
        );
    }

    private User resolveUser(Long id, Map<Long, User> map) {
        if (id == null) return null;
        if (map != null) return map.get(id);
        return userRepository.findById(id).orElse(null);
    }

    private Order resolveOrder(Long demandId, Map<Long, Order> map) {
        if (demandId == null) return null;
        if (map != null) return map.get(demandId);
        return orderRepository.findByDemandId(demandId).orElse(null);
    }

    /**
     * SELECT_MANY 组队进度：已选中人数。仅 SELECT_MANY 模式输出，其余模式返回 null。
     */
    private Integer resolveSelectedParticipantCount(Demand demand) {
        if (demand == null || demand.getId() == null
            || demand.getInteractionMode() != InteractionMode.SELECT_MANY
            || demandResponseRepository == null) {
            return null;
        }
        return (int) demandResponseRepository.countSelectedByDemandId(demand.getId());
    }

    public OrderView toOrderView(Order order, CurrentUser currentUser) {
        return toOrderView(order, currentUser, null, null, null, null, null);
    }

    public OrderView toOrderView(Order order, CurrentUser currentUser,
            Map<Long, Demand> demandMap, Map<Long, User> userMap,
            Map<Long, List<Review>> reviewByOrderIdMap, Set<Long> reviewedOrderIdsByCurrentUser,
            Map<Long, Order> orderByDemandMap) {
        Demand demand = resolveDemand(order.getDemandId(), demandMap);
        User requester = resolveUser(order.getPublisherId(), userMap);
        User provider = resolveUser(order.getAccepterId(), userMap);
        boolean canSeePublisher = demand == null || canSeeDemandPublisher(demand, currentUser);
        String anonymousCode = demand != null ? demand.getAnonymousCode() : null;
        // acceptNote / 履约凭证 / 完整状态历史 / 仲裁内部信息属于交易私密信息，仅订单双方与管理员可见
        boolean canSeeOrderPrivate = currentUser != null
            && (currentUser.isAdmin() || order.isParticipant(currentUser.userId()));

        List<ReviewView> reviews = resolveReviews(order.getId(), reviewByOrderIdMap).stream()
            .map(review -> toReviewView(review, canSeePublisher, order.getPublisherId(), anonymousCode))
            .toList();
        boolean currentUserReviewed = currentUser != null
            && (reviewedOrderIdsByCurrentUser != null
                ? reviewedOrderIdsByCurrentUser.contains(order.getId())
                : reviewRepository.findByOrderIdAndAuthorId(order.getId(), currentUser.userId()).isPresent());

        return new OrderView(
            order.getId(),
            order.getId(),
            order.getStatus().name(),
            order.getDemandId(),
            order.getPublisherId(),
            order.getAccepterId(),
            canSeeOrderPrivate ? order.getAcceptNote() : null,
            canSeeOrderPrivate && order.isProofSubmitted(),
            canSeeOrderPrivate ? order.getProofImageCount() : 0,
            order.getCreatedAt(),
            order.getUpdatedAt(),
            order.getCompletedAt(),
            demand == null ? null : toDemandView(demand, currentUser, userMap, orderByDemandMap),
            requester == null ? null : anonymizePublicUserSummary(PublicUserSummaryView.from(requester), canSeePublisher, anonymousCode),
            provider == null ? null : PublicUserSummaryView.from(provider),
            canSeeOrderPrivate ? order.getStatusHistory().stream().map(this::toTimelineView).toList() : List.of(),
            reviews,
            currentUserReviewed,
            resolvePendingReviewTarget(order, currentUser, currentUserReviewed),
            resolveCompletionHint(order, currentUser),
            demand == null ? List.of() : demand.getImages(),
            resolveContactInfo(demand, order, currentUser),
            canSeeOrderPrivate ? resolveArbitrationResult(order) : null
        );
    }

    private Demand resolveDemand(Long id, Map<Long, Demand> map) {
        if (id == null) return null;
        if (map != null) return map.get(id);
        return demandRepository.findById(id).orElse(null);
    }

    private List<Review> resolveReviews(Long orderId, Map<Long, List<Review>> map) {
        if (orderId == null) return List.of();
        if (map != null) return map.getOrDefault(orderId, List.of());
        return reviewRepository.findByOrderId(orderId);
    }

    public ReviewView toReviewView(Review review) {
        return toReviewView(review, true, null, null);
    }

    public ReviewView toAnonymizedReviewView(ReviewResponse review, CurrentUser currentUser) {
        // 优先用 demandId 反查 Demand 判断匿名性（覆盖 Response Review，其 orderId 为 null）；
        // 仅在 demandId 缺失时 fallback 到 Order 反查
        Demand demand = review.demandId() != null
            ? demandRepository.findById(review.demandId()).orElse(null)
            : resolveDemandViaOrder(review.orderId());
        boolean canSeePublisher = demand == null || canSeeDemandPublisher(demand, currentUser);
        String anonymousCode = demand != null ? demand.getAnonymousCode() : null;
        Long publisherId = demand != null ? demand.getPublisherId() : resolvePublisherIdViaOrder(review.orderId());

        User author = userRepository.findById(review.authorId()).orElse(null);
        User target = userRepository.findById(review.targetId()).orElse(null);

        PublicUserSummaryView authorView = author == null ? null
            : anonymizePublicUserSummary(
                PublicUserSummaryView.from(author),
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

    private Demand resolveDemandViaOrder(Long orderId) {
        if (orderId == null) {
            return null;
        }
        Order order = orderRepository.findById(orderId).orElse(null);
        return order != null ? demandRepository.findById(order.getDemandId()).orElse(null) : null;
    }

    private Long resolvePublisherIdViaOrder(Long orderId) {
        if (orderId == null) {
            return null;
        }
        Order order = orderRepository.findById(orderId).orElse(null);
        return order != null ? order.getPublisherId() : null;
    }

    private ReviewView toReviewView(Review review, boolean canSeePublisher, Long publisherId, String anonymousCode) {
        User author = userRepository.findById(review.getAuthorId()).orElse(null);
        User target = userRepository.findById(review.getTargetId()).orElse(null);
        PublicUserSummaryView authorView = author == null ? null
            : anonymizePublicUserSummary(
                PublicUserSummaryView.from(author),
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

    private PublicUserSummaryView anonymizePublicUserSummary(PublicUserSummaryView original, boolean canSee, String anonymousCode) {
        if (canSee || original == null) {
            return original;
        }
        return new PublicUserSummaryView(
            null,
            anonymousCode != null ? anonymousCode : "匿名校友",
            original.avatarUrl(),
            original.role(),
            original.status(),
            original.creditScore()
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

    /**
     * 联系方式仅对接单人、发布者和管理员可见。
     */
    private String resolveContactInfo(Demand demand, Order relatedOrder, CurrentUser currentUser) {
        if (demand == null || demand.getContactInfo() == null) {
            return null;
        }
        if (currentUser == null) {
            return null;
        }
        if (currentUser.isAdmin()) {
            return demand.getContactInfo();
        }
        if (demand.getPublisherId() != null && demand.getPublisherId().equals(currentUser.userId())) {
            return demand.getContactInfo();
        }
        if (relatedOrder != null && relatedOrder.getAccepterId() != null
            && relatedOrder.getAccepterId().equals(currentUser.userId())) {
            return demand.getContactInfo();
        }
        return null;
    }

    /**
     * 从订单状态历史中提取仲裁裁决结果。
     */
    private String resolveArbitrationResult(Order order) {
        if (order == null || order.getStatusHistory().isEmpty()) {
            return null;
        }
        return order.getStatusHistory().stream()
            .filter(entry -> entry.note() != null && entry.note().startsWith("ARBITRATION_RESOLVED:"))
            .reduce((first, second) -> second) // get the last
            .map(entry -> {
                String reason = entry.note().substring("ARBITRATION_RESOLVED:".length()).trim();
                String outcome = switch (entry.toStatus()) {
                    case COMPLETED -> "完成";
                    case CANCELLED -> "取消";
                    default -> entry.toStatus().name();
                };
                return "裁决结果：已" + outcome + "。说明：" + reason;
            })
            .orElse(null);
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
                && ("PROVIDER_CONFIRMED_COMPLETION".equals(entry.note())
                    || "REQUESTER_CONFIRMED_COMPLETION".equals(entry.note())
                    || "已确认完成，等待对方确认。".equals(entry.note())));
    }
}
