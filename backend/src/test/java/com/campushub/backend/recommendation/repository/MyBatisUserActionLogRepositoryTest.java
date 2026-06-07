package com.campushub.backend.recommendation.repository;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.campushub.backend.demand.domain.DemandCategory;
import com.campushub.backend.recommendation.domain.ActionType;
import com.campushub.backend.recommendation.domain.UserActionLog;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MyBatisUserActionLogRepository} 的切片测试。
 *
 * <p>使用 H2 内存库，通过 {@code @Sql} 单独加载 rec_user_action_log 建表脚本，
 * 覆盖行为日志 INSERT / UPDATE / 用户查询 / 动作类型过滤 / 空值防御等关键路径。</p>
 */
@MybatisPlusTest
@ActiveProfiles("local")
@Import(MyBatisUserActionLogRepository.class)
@Sql(scripts = "classpath:schema-recommendation.sql")
class MyBatisUserActionLogRepositoryTest {

    @Autowired
    private MyBatisUserActionLogRepository repository;

    @Test
    void save_insert_assigns_id_and_findByUserId_returns_persisted_log() {
        UserActionLog log = newUserActionLog(10L, ActionType.VIEW, 1001L, DemandCategory.EXPRESS);

        UserActionLog saved = repository.save(log);

        assertThat(saved.getId()).isNotNull();
        List<UserActionLog> loaded = repository.findByUserId(10L);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getUserId()).isEqualTo(10L);
        assertThat(loaded.get(0).getActionType()).isEqualTo(ActionType.VIEW);
        assertThat(loaded.get(0).getDemandId()).isEqualTo(1001L);
        assertThat(loaded.get(0).getCategory()).isEqualTo(DemandCategory.EXPRESS);
    }

    @Test
    void save_update_when_id_present_changes_action_and_category() {
        UserActionLog log = repository.save(
            newUserActionLog(10L, ActionType.VIEW, 1001L, DemandCategory.EXPRESS)
        );

        log.setActionType(ActionType.ACCEPT);
        log.setCategory(DemandCategory.STUDY_TUTORING);
        repository.save(log);

        List<UserActionLog> loaded = repository.findByUserId(10L);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getActionType()).isEqualTo(ActionType.ACCEPT);
        assertThat(loaded.get(0).getCategory()).isEqualTo(DemandCategory.STUDY_TUTORING);
    }

    @Test
    void findByUserId_returns_all_logs_for_a_user() {
        repository.save(newUserActionLog(10L, ActionType.VIEW, 1001L, DemandCategory.EXPRESS));
        repository.save(newUserActionLog(10L, ActionType.ACCEPT, 1002L, DemandCategory.SECOND_HAND));
        repository.save(newUserActionLog(20L, ActionType.VIEW, 1003L, DemandCategory.TEAM_UP));

        List<UserActionLog> forUser10 = repository.findByUserId(10L);

        assertThat(forUser10).hasSize(2);
        assertThat(forUser10).extracting(UserActionLog::getDemandId)
            .containsExactlyInAnyOrder(1001L, 1002L);
    }

    @Test
    void findByUserId_returns_empty_list_not_null_when_null_or_no_logs() {
        assertThat(repository.findByUserId(null)).isNotNull().isEmpty();
        assertThat(repository.findByUserId(999L)).isNotNull().isEmpty();
    }

    @Test
    void findByUserIdAndActionType_returns_only_matching_action_logs() {
        repository.save(newUserActionLog(10L, ActionType.VIEW, 1001L, DemandCategory.EXPRESS));
        repository.save(newUserActionLog(10L, ActionType.ACCEPT, 1002L, DemandCategory.SECOND_HAND));
        repository.save(newUserActionLog(10L, ActionType.ACCEPT, 1003L, DemandCategory.TEAM_UP));
        repository.save(newUserActionLog(20L, ActionType.ACCEPT, 1004L, DemandCategory.OTHER));

        List<UserActionLog> acceptedLogs = repository.findByUserIdAndActionType(10L, ActionType.ACCEPT);

        assertThat(acceptedLogs).hasSize(2);
        assertThat(acceptedLogs).extracting(UserActionLog::getCategory)
            .containsExactlyInAnyOrder(DemandCategory.SECOND_HAND, DemandCategory.TEAM_UP);
    }

    @Test
    void findByUserIdAndActionType_returns_empty_list_not_null_when_null_or_no_logs() {
        repository.save(newUserActionLog(10L, ActionType.VIEW, 1001L, DemandCategory.EXPRESS));

        assertThat(repository.findByUserIdAndActionType(null, ActionType.VIEW)).isNotNull().isEmpty();
        assertThat(repository.findByUserIdAndActionType(10L, null)).isNotNull().isEmpty();
        assertThat(repository.findByUserIdAndActionType(null, null)).isNotNull().isEmpty();
        assertThat(repository.findByUserIdAndActionType(999L, ActionType.ACCEPT)).isNotNull().isEmpty();
    }

    @Test
    void created_at_is_persisted_and_loaded_correctly() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 18, 12, 30);
        UserActionLog log = newUserActionLog(10L, ActionType.VIEW, 1001L, DemandCategory.EXPRESS);
        log.setCreatedAt(createdAt);

        repository.save(log);

        UserActionLog reloaded = repository.findByUserId(10L).get(0);
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
    }

    /**
     * 工厂方法：为所有 NOT NULL 列（user_id / action_type / demand_id / category / created_at）
     * 提供默认值，避免 H2 抛出 NULL not allowed 异常。
     */
    private static UserActionLog newUserActionLog(
        Long userId,
        ActionType actionType,
        Long demandId,
        DemandCategory category
    ) {
        UserActionLog log = new UserActionLog();
        log.setUserId(userId);
        log.setActionType(actionType);
        log.setDemandId(demandId);
        log.setCategory(category);
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }
}
