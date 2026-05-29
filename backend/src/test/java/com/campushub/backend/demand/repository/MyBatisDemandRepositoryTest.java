package com.campushub.backend.demand.repository;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.campushub.backend.demand.domain.CampusZone;
import com.campushub.backend.demand.domain.Demand;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.demand.domain.DemandStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
}
