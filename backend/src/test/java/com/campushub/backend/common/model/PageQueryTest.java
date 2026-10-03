package com.campushub.backend.common.model;

import com.campushub.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageQueryTest {

    @Test
    void accepts_page_and_size_within_bounds() {
        PageQuery query = new PageQuery(1, 20);
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(20);
    }

    @Test
    void rejects_page_above_max() {
        assertThatThrownBy(() -> new PageQuery(PageQuery.MAX_PAGE + 1, 20))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejects_page_below_one() {
        assertThatThrownBy(() -> new PageQuery(0, 20))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejects_size_out_of_bounds() {
        assertThatThrownBy(() -> new PageQuery(1, 0))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new PageQuery(1, PageQuery.MAX_SIZE + 1))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void defaultPage_returns_defaults() {
        PageQuery query = PageQuery.defaultPage();
        assertThat(query.page()).isEqualTo(PageQuery.DEFAULT_PAGE);
        assertThat(query.size()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    @Test
    void accepts_page_at_max_boundary() {
        PageQuery query = new PageQuery(PageQuery.MAX_PAGE, PageQuery.MAX_SIZE);
        assertThat(query.page()).isEqualTo(PageQuery.MAX_PAGE);
        assertThat(query.size()).isEqualTo(PageQuery.MAX_SIZE);
    }
}
