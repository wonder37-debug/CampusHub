package com.campushub.backend.demand.repository;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.demand.domain.CampusZone;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.demand.domain.DemandSort;
import com.campushub.backend.demand.domain.DemandStatus;
import com.campushub.backend.demand.dto.DemandQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MyBatisDemandRepository} 的切片测试。
 *
 * <p>使用 H2 内存库，通过 {@code @Sql} 单独加载需求表建表脚本，
 * 确保与 sys_user 测试互不干扰。</p>
 */
@MybatisPlusTest
@ActiveProfiles("local")
@Import(MyBatisDemandRepository.class)
@Sql(scripts = "classpath:schema-demand.sql")
class MyBatisDemandRepositoryTest {

    @Autowired
    private MyBatisDemandRepository repository;

    @Test
    void save_insert_assigns_id_and_findById_returns_persisted_demand() {
        Demand demand = newDemand("取快递帮拿", DemandCategory.EXPRESS);

        Demand saved = repository.save(demand);

        assertThat(saved.getId()).isNotNull();
        Optional<Demand> loaded = repository.findById(saved.getId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getTitle()).isEqualTo("取快递帮拿");
        assertThat(loaded.get().getCategory()).isEqualTo(DemandCategory.EXPRESS);
        assertThat(loaded.get().getStatus()).isEqualTo(DemandStatus.PENDING);
        assertThat(loaded.get().getIsApproved()).isFalse();
    }

    @Test
    void save_update_when_id_present_changes_fields_in_place() {
        Demand demand = repository.save(newDemand("辅导高数", DemandCategory.STUDY_TUTORING));

        demand.setTitle("辅导线代");
        demand.setReward(new BigDecimal("50.00"));
        demand.setIsApproved(true);
        repository.save(demand);

        Demand reloaded = repository.findById(demand.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("辅导线代");
        assertThat(reloaded.getReward()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(reloaded.getIsApproved()).isTrue();
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void findById_returns_empty_when_missing() {
        assertThat(repository.findById(9999L)).isEmpty();
        assertThat(repository.findById(null)).isEmpty();
    }

    @Test
    void findAll_returns_empty_list_not_null_when_no_data() {
        List<Demand> all = repository.findAll();
        assertThat(all).isNotNull().isEmpty();
    }

    @Test
    void findByStatus_returns_demands_with_matching_status() {
        Demand pending = newDemand("待接单需求", DemandCategory.EXPRESS);
        Demand reviewing = newDemand("待审核需求", DemandCategory.STUDY_TUTORING);
        reviewing.setStatus(DemandStatus.REVIEWING);
        repository.save(pending);
        repository.save(reviewing);

        List<Demand> reviewingDemands = repository.findByStatus(DemandStatus.REVIEWING);

        assertThat(reviewingDemands).hasSize(1);
        assertThat(reviewingDemands.get(0).getStatus()).isEqualTo(DemandStatus.REVIEWING);
        assertThat(repository.findByStatus(null)).isEmpty();
    }

    @Test
    void tags_are_correctly_saved_and_loaded() {
        Demand demand = newDemand("带标签需求", DemandCategory.OTHER);
        demand.setTags(List.of("加急", "校内", "午间"));

        repository.save(demand);

        Demand reloaded = repository.findById(demand.getId()).orElseThrow();
        assertThat(reloaded.getTags()).containsExactly("加急", "校内", "午间");
    }

    @Test
    void empty_tags_save_and_load_as_empty_list() {
        Demand demand = newDemand("无标签需求", DemandCategory.OTHER);
        demand.setTags(List.of());

        repository.save(demand);

        Demand reloaded = repository.findById(demand.getId()).orElseThrow();
        assertThat(reloaded.getTags()).isNotNull().isEmpty();
    }

    @Test
    void null_tags_save_and_load_as_empty_list() {
        Demand demand = newDemand("null标签需求", DemandCategory.OTHER);
        demand.setTags(null);

        repository.save(demand);

        Demand reloaded = repository.findById(demand.getId()).orElseThrow();
        assertThat(reloaded.getTags()).isNotNull().isEmpty();
    }

    @Test
    void anonymous_fields_are_correctly_persisted() {
        Demand demand = newDemand("匿名需求", DemandCategory.SECOND_HAND);
        demand.setAnonymous(true);
        demand.setAnonymousCode("ABC123");

        repository.save(demand);

        Demand reloaded = repository.findById(demand.getId()).orElseThrow();
        assertThat(reloaded.isAnonymous()).isTrue();
        assertThat(reloaded.getAnonymousCode()).isEqualTo("ABC123");
    }

    @Test
    void campus_zone_and_full_fields_are_persisted() {
        Demand demand = newDemand("仙林取件", DemandCategory.EXPRESS);
        demand.setCampusZone(CampusZone.XIANLIN);
        demand.setLocation("仙林快递站");
        demand.setNote("请送到宿舍楼下");
        demand.setReward(new BigDecimal("15.50"));

        repository.save(demand);

        Demand reloaded = repository.findById(demand.getId()).orElseThrow();
        assertThat(reloaded.getCampusZone()).isEqualTo(CampusZone.XIANLIN);
        assertThat(reloaded.getLocation()).isEqualTo("仙林快递站");
        assertThat(reloaded.getNote()).isEqualTo("请送到宿舍楼下");
        assertThat(reloaded.getReward()).isEqualByComparingTo(new BigDecimal("15.50"));
    }

    @Test
    void findPage_returns_all_visible_sorted_by_created_desc_with_default_time_sort() {
        Demand older = repository.save(newDemandWithCreated("旧需求", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(10)));
        Demand newer = repository.save(newDemandWithCreated("新需求", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(1)));

        DemandQuery query = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20));

        List<Demand> page = repository.findPage(query);
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getId()).isEqualTo(newer.getId());
        assertThat(repository.count(query)).isEqualTo(2L);
    }

    @Test
    void findPage_applies_limit_and_offset_for_pagination() {
        for (int i = 0; i < 5; i++) {
            repository.save(newDemandWithCreated("需求" + i, DemandCategory.OTHER, LocalDateTime.now().minusMinutes(5 - i)));
        }
        DemandQuery page1 = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 2));
        DemandQuery page2 = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(2, 2));
        DemandQuery page3 = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(3, 2));

        assertThat(repository.findPage(page1)).hasSize(2);
        assertThat(repository.findPage(page2)).hasSize(2);
        assertThat(repository.findPage(page3)).hasSize(1);
        assertThat(repository.count(page1)).isEqualTo(5L);
    }

    @Test
    void findPage_filters_by_keyword_on_title_or_description() {
        Demand d1 = repository.save(newDemand("取快递帮拿", DemandCategory.EXPRESS));
        Demand d2 = repository.save(newDemand("辅导高数", DemandCategory.STUDY_TUTORING));
        d2.setDescription("线代答疑辅导");
        repository.save(d2);
        Demand d3 = repository.save(newDemand("无关标题", DemandCategory.OTHER));
        d3.setDescription("无关描述");
        repository.save(d3);

        DemandQuery query = new DemandQuery("辅导", null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20));

        assertThat(repository.findPage(query)).extracting(Demand::getId).containsExactly(d2.getId());
        assertThat(repository.count(query)).isEqualTo(1L);
    }

    @Test
    void findPage_filters_by_category_with_case_insensitive_match() {
        Demand express = repository.save(newDemand("快递", DemandCategory.EXPRESS));
        repository.save(newDemand("辅导", DemandCategory.STUDY_TUTORING));

        DemandQuery query = new DemandQuery(null, "express", null, null, null, null, DemandSort.TIME, new PageQuery(1, 20));

        assertThat(repository.findPage(query)).extracting(Demand::getId).containsExactly(express.getId());
        assertThat(repository.count(query)).isEqualTo(1L);
    }

    @Test
    void findPage_filters_by_campus_zone_with_case_insensitive_match() {
        repository.save(newDemand("仙林", DemandCategory.OTHER));
        Demand gulou = repository.save(newDemand("鼓楼", DemandCategory.OTHER));
        gulou.setCampusZone(CampusZone.GULOU);
        repository.save(gulou);

        DemandQuery query = new DemandQuery(null, null, "gulou", null, null, null, DemandSort.TIME, new PageQuery(1, 20));

        assertThat(repository.findPage(query)).extracting(Demand::getId).containsExactly(gulou.getId());
    }

    @Test
    void findPage_filters_by_location_like() {
        Demand d1 = repository.save(newDemand("d1", DemandCategory.OTHER));
        d1.setLocation("仙林菜鸟驿站");
        repository.save(d1);
        Demand d2 = repository.save(newDemand("d2", DemandCategory.OTHER));
        d2.setLocation("鼓楼教学楼");
        repository.save(d2);

        DemandQuery query = new DemandQuery(null, null, null, "菜鸟", null, null, DemandSort.TIME, new PageQuery(1, 20));

        assertThat(repository.findPage(query)).extracting(Demand::getId).containsExactly(d1.getId());
    }

    @Test
    void findPage_filters_by_start_time_range_and_excludes_null_start_time_when_range_given() {
        Demand withStart = repository.save(newDemand("有开始", DemandCategory.OTHER));
        withStart.setStartTime(LocalDateTime.of(2026, 9, 28, 10, 0));
        repository.save(withStart);
        Demand noStart = repository.save(newDemand("无开始", DemandCategory.OTHER));

        LocalDateTime from = LocalDateTime.of(2026, 9, 28, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 28, 23, 59);
        DemandQuery ranged = new DemandQuery(null, null, null, null, from, to, DemandSort.TIME, new PageQuery(1, 20));
        assertThat(repository.findPage(ranged)).extracting(Demand::getId).containsExactly(withStart.getId());

        DemandQuery all = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20));
        assertThat(repository.findPage(all)).extracting(Demand::getId).contains(withStart.getId(), noStart.getId());
    }

    @Test
    void findPage_excludes_reviewing_and_expired_but_includes_own_when_current_user_id_present() {
        Demand pending = repository.save(newDemand("公开待接", DemandCategory.EXPRESS));
        Demand reviewing = repository.save(newDemand("审核中", DemandCategory.EXPRESS));
        reviewing.setStatus(DemandStatus.REVIEWING);
        repository.save(reviewing);
        Demand expired = repository.save(newDemand("已过期", DemandCategory.EXPRESS));
        expired.setEndTime(LocalDateTime.now().minusDays(1));
        repository.save(expired);

        DemandQuery publicQuery = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20));
        List<Demand> publicPage = repository.findPage(publicQuery);
        assertThat(publicPage).extracting(Demand::getId).contains(pending.getId());
        assertThat(publicPage).extracting(Demand::getId).doesNotContain(reviewing.getId(), expired.getId());

        DemandQuery ownQuery = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 20), pending.getPublisherId());
        List<Demand> ownPage = repository.findPage(ownQuery);
        assertThat(ownPage).extracting(Demand::getId).contains(reviewing.getId(), expired.getId());
    }

    @Test
    void findPage_sorts_by_reward_desc_then_created_desc() {
        Demand high = repository.save(newDemandWithCreated("高报酬", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(5)));
        high.setReward(new BigDecimal("50.00"));
        repository.save(high);
        Demand low = repository.save(newDemandWithCreated("低报酬", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(1)));
        low.setReward(new BigDecimal("10.00"));
        repository.save(low);
        Demand zero = repository.save(newDemandWithCreated("零报酬", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(10)));
        zero.setReward(BigDecimal.ZERO);
        repository.save(zero);

        DemandQuery query = new DemandQuery(null, null, null, null, null, null, DemandSort.REWARD, new PageQuery(1, 20));

        assertThat(repository.findPage(query)).extracting(Demand::getId)
            .containsExactly(high.getId(), low.getId(), zero.getId());
    }

    @Test
    void findPage_recommend_sort_equals_created_at_desc() {
        Demand older = repository.save(newDemandWithCreated("旧", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(10)));
        Demand newer = repository.save(newDemandWithCreated("新", DemandCategory.OTHER, LocalDateTime.now().minusMinutes(1)));

        DemandQuery query = new DemandQuery(null, null, null, null, null, null, DemandSort.RECOMMEND, new PageQuery(1, 20));

        assertThat(repository.findPage(query)).extracting(Demand::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void count_matches_findPage_total_for_filtered_query() {
        repository.save(newDemand("快递一", DemandCategory.EXPRESS));
        repository.save(newDemand("辅导", DemandCategory.STUDY_TUTORING));
        repository.save(newDemand("快递二", DemandCategory.EXPRESS));

        DemandQuery query = new DemandQuery(null, "EXPRESS", null, null, null, null, DemandSort.TIME, new PageQuery(1, 20));

        assertThat(repository.count(query)).isEqualTo(2L);
        assertThat(repository.findPage(query)).hasSize(2);
    }

    @Test
    void findPage_and_count_return_empty_when_query_null() {
        assertThat(repository.findPage(null)).isEmpty();
        assertThat(repository.count(null)).isEqualTo(0L);
    }

    @Test
    void findPage_with_same_created_at_is_deterministic_across_pages() {
        LocalDateTime sameTime = LocalDateTime.now();
        Demand d1 = repository.save(newDemandWithCreated("d1", DemandCategory.OTHER, sameTime));
        Demand d2 = repository.save(newDemandWithCreated("d2", DemandCategory.OTHER, sameTime));
        Demand d3 = repository.save(newDemandWithCreated("d3", DemandCategory.OTHER, sameTime));
        Demand d4 = repository.save(newDemandWithCreated("d4", DemandCategory.OTHER, sameTime));

        DemandQuery page1 = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(1, 2));
        DemandQuery page2 = new DemandQuery(null, null, null, null, null, null, DemandSort.TIME, new PageQuery(2, 2));

        List<Demand> first = repository.findPage(page1);
        List<Demand> second = repository.findPage(page2);

        List<Long> allIds = new ArrayList<>(first.stream().map(Demand::getId).toList());
        allIds.addAll(second.stream().map(Demand::getId).toList());
        assertThat(allIds).containsExactlyInAnyOrder(d1.getId(), d2.getId(), d3.getId(), d4.getId());
        Set<Long> firstIds = first.stream().map(Demand::getId).collect(Collectors.toSet());
        second.forEach(d -> assertThat(firstIds).doesNotContain(d.getId()));
    }

    private static Demand newDemand(String title, DemandCategory category) {
        Demand demand = new Demand();
        demand.setPublisherId(1L);
        demand.setTitle(title);
        demand.setDescription("测试需求描述");
        demand.setCategory(category);
        // ord_demand.campus_zone 为 NOT NULL，提供默认值避免约束冲突；
        // 个别需要校验该字段的用例会在测试体内显式覆盖。
        demand.setCampusZone(CampusZone.XIANLIN);
        demand.setReward(BigDecimal.ZERO);
        demand.setStatus(DemandStatus.PENDING);
        demand.setIsApproved(false);
        demand.setAnonymous(false);
        demand.setCreatedAt(LocalDateTime.now());
        return demand;
    }

    private static Demand newDemandWithCreated(String title, DemandCategory category, LocalDateTime createdAt) {
        Demand demand = newDemand(title, category);
        demand.setCreatedAt(createdAt);
        return demand;
    }
}
