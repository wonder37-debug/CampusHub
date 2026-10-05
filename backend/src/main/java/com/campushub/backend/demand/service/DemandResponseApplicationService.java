package com.campushub.backend.demand.service;

import com.campushub.backend.demand.dto.CreateDemandResponseCommand;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.DemandResponseDetail;
import com.campushub.backend.demand.dto.SelectResponsesCommand;
import com.campushub.backend.order.dto.OrderDetailResponse;

import java.util.List;

public interface DemandResponseApplicationService {

    /** 创建响应（留言/报名/回答）。DIRECT_ACCEPT 模式不支持。 */
    DemandResponseDetail createResponse(Long operatorId, Long demandId, CreateDemandResponseCommand command);

    /** 列出 Demand 的所有响应。 */
    List<DemandResponseDetail> listResponses(Long demandId);

    /** SELECT_ONE：发布者选择一个 Response，创建 Order 进入履约。 */
    OrderDetailResponse selectResponse(Long operatorId, Long demandId, Long responseId);

    /** SELECT_MANY：发布者选择多个 Response，达到目标人数后立即完成并平分 reward。 */
    DemandDetailResponse selectResponses(Long operatorId, Long demandId, SelectResponsesCommand command);

    /** HELP：发布者采纳一个回答，Demand 完成并结算 reward 给被采纳者。 */
    DemandDetailResponse acceptAnswer(Long operatorId, Long demandId, Long responseId);

    /** 作者撤回自己的响应（仅 PENDING 可撤回）。 */
    DemandResponseDetail withdrawResponse(Long operatorId, Long responseId);
}
