package com.campushub.backend.admin.service;

import com.campushub.backend.admin.dto.AdminDashboardResponse;
import com.campushub.backend.admin.dto.AdminDemandQuery;
import com.campushub.backend.admin.dto.AdminDemandReviewCommand;
import com.campushub.backend.admin.dto.AdminOrderArbitrationCommand;
import com.campushub.backend.admin.dto.AdminUserQuery;
import com.campushub.backend.auth.dto.UserProfileResponse;
import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.order.dto.OrderDetailResponse;

public interface AdminApplicationService {

    PageResponse<UserProfileResponse> listUsers(Long operatorId, AdminUserQuery query);

    UserProfileResponse banUser(Long operatorId, Long userId, String reason);

    UserProfileResponse unbanUser(Long operatorId, Long userId);

    UserProfileResponse updateUserRole(Long operatorId, Long userId, String role);

    PageResponse<DemandSummaryResponse> listPendingDemands(Long operatorId, AdminDemandQuery query);

    DemandDetailResponse reviewDemand(Long operatorId, Long demandId, AdminDemandReviewCommand command);

    AdminDashboardResponse getDashboard(Long operatorId);

    void deleteOrder(Long operatorId, Long orderId, String reason);

    OrderDetailResponse resolveOrderArbitration(Long operatorId, Long orderId, AdminOrderArbitrationCommand command);
}
