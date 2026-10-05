package com.campushub.backend.order.service;

import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.order.dto.AcceptOrderCommand;
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

    OrderDetailResponse requestArbitration(Long operatorId, Long orderId, RequestOrderArbitrationCommand command);
}
