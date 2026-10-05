package com.campushub.backend.demand.dto;

import java.util.List;

public record SelectResponsesCommand(
    List<Long> responseIds
) {
}
