package com.campushub.backend.demand.service;

import com.campushub.backend.common.api.PageResponse;
import com.campushub.backend.demand.dto.DemandDetailResponse;
import com.campushub.backend.demand.dto.DemandQuery;
import com.campushub.backend.demand.dto.DemandSummaryResponse;
import com.campushub.backend.demand.dto.PublishDemandCommand;
import com.campushub.backend.demand.dto.UpdateDemandCommand;

public interface DemandApplicationService {

    DemandDetailResponse publish(Long publisherId, PublishDemandCommand command);

    PageResponse<DemandSummaryResponse> list(DemandQuery query);

    DemandDetailResponse getDetail(Long demandId);

    DemandDetailResponse update(Long operatorId, Long demandId, UpdateDemandCommand command);
}
