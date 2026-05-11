package com.campushub.backend.admin.dto;

import java.util.List;

public record AdminDashboardResponse(
    long dailyActiveUsers,
    long totalUsers,
    long totalDemands,
    long pendingReviewDemands,
    long totalOrders,
    long completedOrders,
    List<AdminCategoryStatResponse> categoryDistribution
) {
}
