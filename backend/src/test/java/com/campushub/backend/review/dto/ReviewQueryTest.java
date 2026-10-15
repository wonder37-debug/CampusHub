package com.campushub.backend.review.dto;

import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.model.PageQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReviewQueryTest {

    @Test
    void invalid_direction_throws_validation_error() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> new ReviewQuery(new PageQuery(1, 20), "invalid"));
        assertThat(ex.getMessage()).contains("invalid review direction");
    }

    @Test
    void whitespace_direction_throws_validation_error() {
        assertThrows(BusinessException.class,
            () -> new ReviewQuery(new PageQuery(1, 20), "xyz"));
    }

    @Test
    void null_direction_defaults_to_all() {
        ReviewQuery query = new ReviewQuery(new PageQuery(1, 20), null);
        assertThat(query.direction()).isEqualTo(ReviewQuery.DIRECTION_ALL);
    }

    @Test
    void uppercase_direction_is_normalized_and_accepted() {
        ReviewQuery query = new ReviewQuery(new PageQuery(1, 20), "RECEIVED");
        assertThat(query.direction()).isEqualTo(ReviewQuery.DIRECTION_RECEIVED);
    }
}
