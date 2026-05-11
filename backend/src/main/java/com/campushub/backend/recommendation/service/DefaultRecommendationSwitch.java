package com.campushub.backend.recommendation.service;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class DefaultRecommendationSwitch implements RecommendationSwitch {

    @Override
    public boolean enabled() {
        return true;
    }
}
