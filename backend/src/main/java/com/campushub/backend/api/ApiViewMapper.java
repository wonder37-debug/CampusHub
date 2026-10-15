package com.campushub.backend.api;

import com.campushub.backend.api.view.ArbitrationSubmissionView;
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
import com.campushub.backend.order.domain.ArbitrationSubmission;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;
import com.campushub.backend.order.repository.ArbitrationSubmissionRepository;
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
    private final ArbitrationSubmissionRepository arbitrationSubmissionRepository;

    public ApiViewMapper(
        DemandRepository demandRepository,
        UserRepository userRepository,
        OrderRepository orderRepository,
        ReviewRepository reviewRepository,
        DemandResponseRepository demandResponseRepository,
        ArbitrationSubmissionRepository arbitrationSubmissionRepository
    ) {
        this.demandRepository = demandRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
        this.demandResponseRepository = demandResponseRepository;
        this.arbitrationSubmissionRepository = arbitrationSubmissionRepository;
    }

    public DemandView toDemandView(Demand demand, CurrentUser currentUser) {
        return toDemandView(demand, currentUser, null, null);
    }

    public DemandView toDemandView(Demand demand, CurrentUser currentUser, Map<Long, User> userMap, Map<Long, Order> orderMap) {
        return toDemandView(demand, currentUser, userMap, orderMap, null);
    }

    public DemandView toDemandView(Demand demand, CurrentUser currentUser, Map<Long, User> userMap, Map<Long, Order> orderMap, Map<Long, Long> selectedCountMap) {
        boolean canSeePublisher = canSeeDemandPublisher(demand, currentUser);
        User publisherUser = demand.getPublisherId() == null ? null : resolveUser(demand.getPublisherId(), userMap);
        Long publisherId = canSeePublisher ? demand.getPublisherId() : null;
        // publisher_display_name 是发布时的反范式快照，用户改昵称后会过期；
        // 统一优先使用 users 表的实时昵称，仅在用户无法解析时回退快照值，避免列表与详情昵称不一致。
        String publisherDisplayName = canSeePublisher
            ? resolveLivePublisherDisplayName(publisherUser, demand.getPublisherDisplayName())
            : demand.getAnonymousCode();
        PublicUserSummaryView publisher = publisherUser == null
            ? null
            : canSeePublisher
                ? PublicUserSummaryView.from(publisherUser)
                : new PublicUserSummaryView(
                    null,
                    demand.getAnonymousCode() != null ? demand.getAnonymousCode() : "匿名校友",
                    // 真实头像同样是身份线索：无权识别发布者的查看者不返回（前端回退到匿名代号生成的通用头像）
                    null,
                    publisherUser.getRole().name(),
                    publisherUser.getStatus().name(),
                    publisherUser.getCreditScore()
                );

        boolean publisherIdentityVisible = publisherUser != null
            && (!demand.isAnonymous()
            || currentUser != null && (currentUser.isAdmin() || demand.getPublisherId().equals(currentUser.userId())));
        // 匿名需求下无权识别发布者时，部分学号同样是身份线索，返回 null；
        // 非匿名与本人/管理员维持既有规则（identityVisible 为 true 时输出完整学号）
        String publisherStudentIdMasked = publisherUser == null || publisherUser.getStudentId() == null
            ? null
            : demand.isAnonymous() && !publisherIdentityVisible
                ? null
                : publisherUser.getStudentId();

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
            resolveSelectedParticipantCount(demand, selectedCountMap),
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

    /**
     * 解析发布者显示名：优先取 users 表实时昵称，用户不存在或昵称为空时回退需求快照。
     */
    private String resolveLivePublisherDisplayName(User publisherUser, String snapshotDisplayName) {
        if (publisherUser != null && publisherUser.getNickname() != null && !publisherUser.getNickname().isBlank()) {
            return publisherUser.getNickname();
        }
        return snapshotDisplayName;
    }

    private Order resolveOrder(Long demandId, Map<Long, Order> map) {
        if (demandId == null) return null;
        if (map != null) return map.get(demandId);
        return orderRepository.findByDemandId(demandId).orElse(null);
    }

    /**
     * SELECT_MANY 组队进度：已选中人数。仅 SELECT_MANY 模式输出，其余模式返回 null。
     * 列表场景传入 selectedCountMap（batch 预加载）避免 N+1；单条场景 fallback 到 countSelectedByDemandId。
     */
    private Integer resolveSelectedParticipantCount(Demand demand, Map<Long, Long> selectedCountMap) {
        if (demand == null || demand.getId() == null
            || demand.getInteractionMode() != InteractionMode.SELECT_MANY) {
            return null;
        }
        if (selectedCountMap != null) {
            Long count = selectedCountMap.get(demand.getId());
            return count == null ? 0 : count.intValue();
        }
        if (demandResponseRepository == null) {
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

        List<ArbitrationSubmissionView> arbitrationSubmissions = canSeeOrderPrivate
            ? resolveArbitrationSubmissions(order, demand, currentUser) : List.of();

        boolean canSubmitArbitrationResponse = canSeeOrderPrivate
            && order.getStatus() == OrderStatus.IN_ARBITRATION
            && currentUser != null
            && order.isParticipant(currentUser.userId())
            && !arbitrationSubmissions.stream()
                .anyMatch(s -> "INITIATOR".equals(s.role()) && currentUser.userId().equals(s.submitterId()));

        List<ReviewView> reviews = resolveReviews(order.getId(), reviewByOrderIdMap).stream()
            .map(review -> toReviewView(review, canSeePublisher, order.getPublisherId(), anonymousCode,
                demand != null ? demand.getTitle() : null))
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
            // 匿名需求下无权识别发布者的查看者不得拿到真实 publisherId（可通过用户接口反查真实昵称）
            canSeePublisher ? order.getPublisherId() : null,
            order.getAccepterId(),
            canSeeOrderPrivate ? order.getAcceptNote() : null,
            canSeeOrderPrivate && order.isProofSubmitted(),
            canSeeOrderPrivate ? order.getProofImageCount() : 0,
            canSeeOrderPrivate ? order.getProofImageUrls() : null,
            order.getCreatedAt(),
            order.getUpdatedAt(),
            order.getCompletedAt(),
            demand == null ? null : toDemandView(demand, currentUser, userMap, orderByDemandMap),
            requester == null ? null : anonymizePublicUserSummary(PublicUserSummaryView.from(requester), canSeePublisher, anonymousCode),
            provider == null ? null : PublicUserSummaryView.from(provider),
            canSeeOrderPrivate ? order.getStatusHistory().stream()
                .map(entry -> toTimelineView(entry, order.getPublisherId(), canSeePublisher)).toList() : List.of(),
            reviews,
            currentUserReviewed,
            resolvePendingReviewTarget(order, currentUser, currentUserReviewed, canSeePublisher),
            resolveCompletionHint(order, currentUser),
            demand == null ? List.of() : demand.getImages(),
            resolveContactInfo(demand, order, currentUser),
            canSeeOrderPrivate ? resolveArbitrationResult(order) : null,
            arbitrationSubmissions,
            canSubmitArbitrationResponse
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

    /**
     * 单条评价转换（fallback 到 repository 查询需求标题）。
     */
    public ReviewView toReviewView(Review review) {
        return toReviewView(review, true, null, null, null);
    }

    /**
     * 批量场景传入预加载的 demandTitle，避免每条评价重复查询 Demand（N+1）。
     */
    private ReviewView toReviewView(Review review, boolean canSeePublisher, Long publisherId, String anonymousCode, String preloadedDemandTitle) {
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

        // 优先使用预加载的 demandTitle（批量场景），fallback 到 repository 查询（单条场景）
        String demandTitle = preloadedDemandTitle;
        if (demandTitle == null) {
            if (review.getDemandId() != null) {
                demandTitle = demandRepository.findById(review.getDemandId()).map(Demand::getTitle).orElse(null);
            }
            if (demandTitle == null && review.getOrderId() != null) {
                Order order = orderRepository.findById(review.getOrderId()).orElse(null);
                if (order != null && order.getDemandId() != null) {
                    demandTitle = demandRepository.findById(order.getDemandId()).map(Demand::getTitle).orElse(null);
                }
            }
        }

        return new ReviewView(
            review.getId(),
            review.getOrderId(),
            review.getResponseId(),
            review.getDemandId(),
            demandTitle,
            review.getRating(),
            review.getComment(),
            (!canSeePublisher && publisherId != null && publisherId.equals(review.getTargetId()))
                ? null : review.getTargetId(),
            targetName,
            authorView,
            review.getCreatedAt()
        );
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
            review.responseId(),
            review.demandId(),
            demand != null ? demand.getTitle() : null,
            review.rating(),
            review.comment(),
            (!canSeePublisher && publisherId != null && publisherId.equals(review.targetId()))
                ? null : review.targetId(),
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
            // 匿名摘要不携带真实头像（订单 requester / 评价 author 同规则），前端回退到通用头像
            null,
            original.role(),
            original.status(),
            original.creditScore()
        );
    }

    private OrderTimelineView toTimelineView(OrderStatusHistoryEntry entry, Long publisherId, boolean canSeePublisher) {
        // 匿名需求下发布者操作的时间线条目不得携带其真实 operatorId
        Long operatorId = !canSeePublisher && publisherId != null && publisherId.equals(entry.operatorId())
            ? null : entry.operatorId();
        return new OrderTimelineView(
            entry.changedAt(),
            entry.fromStatus() == null ? null : entry.fromStatus().name(),
            entry.toStatus().name(),
            operatorId,
            entry.note()
        );
    }

    /**
     * 联系方式仅对接单人、发布者、已选中的组队/采纳参与者和管理员可见。
     *
     * <p>SELECT_MANY 组队与 HELP 采纳不创建正式 Order，被选中参与者通过 DemandResponse(SELECTED) 授权；
     * 未选中的报名者、游客与普通第三方仍不可见。</p>
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
        if (demand.getId() != null && demandResponseRepository != null
            && demandResponseRepository.existsSelectedByDemandIdAndAuthorId(demand.getId(), currentUser.userId())) {
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

    private List<ArbitrationSubmissionView> resolveArbitrationSubmissions(Order order, Demand demand, CurrentUser currentUser) {
        if (order == null || arbitrationSubmissionRepository == null) {
            return List.of();
        }
        List<ArbitrationSubmission> submissions = arbitrationSubmissionRepository.findByOrderId(order.getId());
        boolean isAnonymous = demand != null && demand.isAnonymous();
        Long publisherId = demand != null ? demand.getPublisherId() : null;
        String anonymousCode = demand != null ? demand.getAnonymousCode() : null;
        // 匿名需求：发布方提交者对管理员和发布者本人显示真实昵称，对其他人显示匿名代号
        boolean canSeePublisherIdentity = !isAnonymous
            || (currentUser != null && (currentUser.isAdmin() || currentUser.userId().equals(publisherId)));
        return submissions.stream()
            .map(s -> {
                String submitterName;
                boolean isPublisherSubmission = isAnonymous && publisherId != null && publisherId.equals(s.submitterId());
                if (isPublisherSubmission && !canSeePublisherIdentity) {
                    submitterName = anonymousCode != null ? anonymousCode : "匿名校友";
                } else {
                    User submitter = userRepository.findById(s.submitterId()).orElse(null);
                    submitterName = submitter != null ? submitter.getNickname() : "用户" + s.submitterId();
                }
                // submitterId 与名称遵循同一规则：发布方举证的真实 ID 不得暴露给无权识别发布者的查看者
                Long submitterId = isPublisherSubmission && !canSeePublisherIdentity ? null : s.submitterId();
                return new ArbitrationSubmissionView(
                    s.id(), submitterId, submitterName, s.role(), s.reason(),
                    s.imageUrls(), s.imageCount(), s.submittedAt()
                );
            })
            .toList();
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

    private Long resolvePendingReviewTarget(Order order, CurrentUser currentUser, boolean currentUserReviewed, boolean canSeePublisher) {
        if (order == null || currentUser == null || currentUserReviewed || order.getStatus() != OrderStatus.COMPLETED) {
            return null;
        }
        if (currentUser.userId().equals(order.getPublisherId())) {
            return order.getAccepterId();
        }
        if (currentUser.userId().equals(order.getAccepterId())) {
            // 匿名需求下接单方无权得知发布者真实 ID，待评价对象仅由前端按角色展示
            return canSeePublisher ? order.getPublisherId() : null;
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
                    || "REQUESTER_CONFIRMED_COMPLETION".equals(entry.note())));
    }
}
