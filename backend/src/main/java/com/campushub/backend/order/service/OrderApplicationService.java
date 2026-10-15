package com.campushub.backend.order.service;

import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.order.dto.AcceptOrderCommand;
import com.campushub.backend.order.dto.ArbitrationSubmissionCommand;
import com.campushub.backend.order.dto.OrderDetailResponse;
import com.campushub.backend.order.dto.OrderHistoryQuery;
import com.campushub.backend.order.dto.OrderSummaryResponse;
import com.campushub.backend.order.dto.RequestOrderArbitrationCommand;
import com.campushub.backend.order.dto.UpdateOrderStatusCommand;

public interface OrderApplicationService {

    OrderDetailResponse accept(Long operatorId, Long demandId, AcceptOrderCommand command);

    /**
     * SELECT_ONE 流程：发布者选中某个 Response 后，为该响应作者创建 Order。
     * 复用现有 Order 状态机、评价与仲裁体系。
     */
    OrderDetailResponse createOrderForSelectedResponse(Long operatorId, Long demandId, Long accepterId, String note);

    OrderDetailResponse updateStatus(Long operatorId, Long orderId, UpdateOrderStatusCommand command);

    OrderDetailResponse getDetail(Long operatorId, Long orderId);

    PageResponse<OrderSummaryResponse> listHistory(Long operatorId, OrderHistoryQuery query);

    void autoCompleteOverdueOrders(Long userId);

    /**
     * 单笔订单的超时自动完成（发布触发与定时调度共用）：
     * 加 Order 行锁后重新校验状态与“接单方确认后 48 小时未确认”窗口，幂等——并发/重复调用只结算一次。
     *
     * @param orderId 订单 ID
     * @param operatorUserId 触发来源用户 ID；定时任务调用时传 null，状态历史 operator 为空表示系统触发
     */
    void autoCompleteOverdueOrder(Long orderId, Long operatorUserId);

    OrderDetailResponse requestArbitration(Long operatorId, Long orderId, RequestOrderArbitrationCommand command);

    OrderDetailResponse submitArbitrationResponse(Long operatorId, Long orderId, ArbitrationSubmissionCommand command);
}
