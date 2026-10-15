package com.campushub.backend.review.dto;

import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import com.campushub.backend.common.model.PageQuery;

/**
 * 用户评价分页查询。
 *
 * <p>direction 控制公开主页与本人工作台的语义差异：</p>
 * <ul>
 *   <li>{@code null} / {@code all}：双向查询（收到的 + 发出的），供本人“我的评价”使用</li>
 *   <li>{@code received}：仅收到的评价（target_id 命中），供公开信任档案展示</li>
 *   <li>{@code given}：仅发出的评价（author_id 命中）</li>
 * </ul>
 */
public record ReviewQuery(PageQuery pageQuery, String direction) {

    public static final String DIRECTION_ALL = "all";
    public static final String DIRECTION_RECEIVED = "received";
    public static final String DIRECTION_GIVEN = "given";

    public ReviewQuery {
        pageQuery = pageQuery == null ? PageQuery.defaultPage() : pageQuery;
        String normalized = direction == null || direction.isBlank()
            ? DIRECTION_ALL
            : direction.trim().toLowerCase(java.util.Locale.ROOT);
        if (!DIRECTION_ALL.equals(normalized)
            && !DIRECTION_RECEIVED.equals(normalized)
            && !DIRECTION_GIVEN.equals(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "invalid review direction: " + normalized + "; must be one of: all, received, given");
        }
        direction = normalized;
    }

    /**
     * 兼容既有调用方：不指定方向时保持双向查询语义。
     */
    public ReviewQuery(PageQuery pageQuery) {
        this(pageQuery, DIRECTION_ALL);
    }
}
