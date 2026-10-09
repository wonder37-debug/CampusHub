package com.campushub.backend.order.dto;

import java.util.List;

/**
 * 更新订单状态命令。
 *
 * <p>{@code proofImageUrls} 为完成凭证图片 URL 列表，仅接单方提交完成时必填（1-3 张）；
 * {@code proofImageCount} 保留作冗余字段，与 {@code proofImageUrls} 不一致时以 urls 为准。</p>
 *
 * <p>保留 3 参数构造器以兼容历史调用方（非完成流转场景 count/urls 均可省略）。</p>
 */
public record UpdateOrderStatusCommand(String targetStatus, String note, Integer proofImageCount, List<String> proofImageUrls) {

    public UpdateOrderStatusCommand(String targetStatus, String note, Integer proofImageCount) {
        this(targetStatus, note, proofImageCount, null);
    }
}
