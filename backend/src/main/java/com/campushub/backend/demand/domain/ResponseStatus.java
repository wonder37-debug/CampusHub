package com.campushub.backend.demand.domain;

/**
 * 需求响应状态枚举。
 *
 * <p>PENDING：待选择/采纳。
 * <p>SELECTED：已被发布者选中（SELECT_ONE/SELECT_MANY）。
 * <p>REJECTED：被发布者拒绝。
 * <p>WITHDRAWN：作者主动撤回。
 */
public enum ResponseStatus {
    PENDING,
    SELECTED,
    REJECTED,
    WITHDRAWN
}
