package com.campushub.backend.api.view;

/**
 * 公开用户统计数据，用于公开个人主页展示。
 * completedOrders 基于真实正式 Order 聚合，不含响应/报名/回答或需求发布数量。
 */
public record UserStatsView(
    int completedOrders
) {
}
