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

    OrderDetailResponse updateStatus(Long operatorId, Long orderId, UpdateOrderStatusCommand command);

    OrderDetailResponse getDetail(Long operatorId, Long orderId);

    PageResponse<OrderSummaryResponse> listHistory(Long operatorId, OrderHistoryQuery query);

    void autoCompleteOverdueOrders(Long userId);

    OrderDetailResponse requestArbitration(Long operatorId, Long orderId, RequestOrderArbitrationCommand command);
}
