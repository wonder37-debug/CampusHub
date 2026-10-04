package com.campushub.backend.order.repository;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.campushub.backend.common.model.PageQuery;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;
import com.campushub.backend.order.dto.OrderHistoryQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MyBatisOrderRepository} 的切片测试。
 *
 * <p>使用 H2 内存库，通过 {@code @Sql} 单独加载 ord_order / ord_order_status_log 建表脚本，
 * 严格覆盖一主多从结构、流水拼装、参与方查询与并发抢单防重等关键路径。</p>
 */
@MybatisPlusTest
@ActiveProfiles("local")
@Import(MyBatisOrderRepository.class)
@Sql(scripts = "classpath:schema-order.sql")
class MyBatisOrderRepositoryTest {

    @Autowired
    private MyBatisOrderRepository repository;

    @Test
    void save_insert_assigns_id_and_findById_returns_persisted_order() {
        Order order = newOrder(1001L, 10L, 20L);

        Order saved = repository.save(order);

        assertThat(saved.getId()).isNotNull();
        Optional<Order> loaded = repository.findById(saved.getId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDemandId()).isEqualTo(1001L);
        assertThat(loaded.get().getPublisherId()).isEqualTo(10L);
        assertThat(loaded.get().getAccepterId()).isEqualTo(20L);
        assertThat(loaded.get().getStatus()).isEqualTo(OrderStatus.ACCEPTED);
    }

    @Test
    void save_update_when_id_present_changes_fields_in_place() {
        Order order = repository.save(newOrder(1002L, 10L, 20L));

        order.setStatus(OrderStatus.IN_PROGRESS);
        order.setAcceptNote("已出发");
        repository.save(order);

        Order reloaded = repository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
        assertThat(reloaded.getAcceptNote()).isEqualTo("已出发");
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void findById_returns_empty_when_missing() {
        assertThat(repository.findById(9999L)).isEmpty();
        assertThat(repository.findById(null)).isEmpty();
    }

    @Test
    void findAll_returns_empty_list_not_null_when_no_data() {
        List<Order> all = repository.findAll();
        assertThat(all).isNotNull().isEmpty();
    }

    @Test
    void status_history_is_persisted_and_loaded_in_chronological_order() {
        Order order = newOrder(1003L, 10L, 20L);
        LocalDateTime t0 = LocalDateTime.of(2026, 5, 16, 9, 0);
        order.addHistory(null, OrderStatus.ACCEPTED, 20L, "接单", t0);
        Order saved = repository.save(order);

        // UPDATE 路径下追加新流水
        saved.setStatus(OrderStatus.IN_PROGRESS);
        saved.addHistory(OrderStatus.ACCEPTED, OrderStatus.IN_PROGRESS, 20L,
            "出发", t0.plusMinutes(30));
        repository.save(saved);

        saved.setStatus(OrderStatus.COMPLETED);
        saved.addHistory(OrderStatus.IN_PROGRESS, OrderStatus.COMPLETED, 10L,
            "已收货", t0.plusHours(1));
        repository.save(saved);

        Order reloaded = repository.findById(saved.getId()).orElseThrow();
        List<OrderStatusHistoryEntry> history = reloaded.getStatusHistory();
        assertThat(history).hasSize(3);
        assertThat(history.get(0).toStatus()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(history.get(1).toStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
        assertThat(history.get(1).fromStatus()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(history.get(2).toStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(history.get(2).note()).isEqualTo("已收货");
    }

    @Test
    void findByDemandId_returns_order_with_history() {
        Order order = newOrder(1004L, 10L, 20L);
        order.addHistory(null, OrderStatus.ACCEPTED, 20L, "接单",
            LocalDateTime.of(2026, 5, 16, 10, 0));
        repository.save(order);

        Optional<Order> found = repository.findByDemandId(1004L);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(order.getId());
        assertThat(found.get().getStatusHistory()).hasSize(1);

        assertThat(repository.findByDemandId(8888L)).isEmpty();
        assertThat(repository.findByDemandId(null)).isEmpty();
    }

    @Test
    void findByParticipant_matches_publisher_or_accepter() {
        repository.save(newOrder(2001L, 10L, 20L)); // 10 发单 / 20 接单
        repository.save(newOrder(2002L, 11L, 10L)); // 10 接单 / 11 发单
        repository.save(newOrder(2003L, 30L, 40L)); // 与 10 无关

        List<Order> mine = repository.findByParticipant(10L);
        assertThat(mine).hasSize(2);
        assertThat(mine).extracting(Order::getDemandId)
            .containsExactlyInAnyOrder(2001L, 2002L);

        assertThat(repository.findByParticipant(999L)).isNotNull().isEmpty();
        assertThat(repository.findByParticipant(null)).isNotNull().isEmpty();
    }

    @Test
    void findAll_returns_all_orders_with_their_history() {
        Order o1 = newOrder(3001L, 10L, 20L);
        o1.addHistory(null, OrderStatus.ACCEPTED, 20L, "A",
            LocalDateTime.of(2026, 5, 16, 8, 0));
        repository.save(o1);

        Order o2 = newOrder(3002L, 11L, 21L);
        repository.save(o2);

        List<Order> all = repository.findAll();
        assertThat(all).hasSize(2);
        Order loaded1 = all.stream().filter(o -> o.getDemandId().equals(3001L))
            .findFirst().orElseThrow();
        assertThat(loaded1.getStatusHistory()).hasSize(1);
    }

    /**
     * 并发抢单防重底线：往同一 demand_id 写入第二条订单，
     * 必须由 ord_order.uk_order_demand 唯一索引拒绝，
     * 异常以 {@link DuplicateKeyException}/{@link DataIntegrityViolationException}
     * 形式向上抛出，绝不被仓储层吞掉。
     */
    @Test
    void duplicate_demand_id_triggers_unique_constraint_violation() {
        Order first = repository.save(newOrder(4001L, 10L, 20L));
        assertThat(first.getId()).isNotNull();

        Order second = newOrder(4001L, 10L, 30L); // 同 demandId，不同接单人
        assertThatThrownBy(() -> repository.save(second))
            .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class);
    }

    @Test
    void findArbitrationPage_returns_only_in_arbitration_orders() {
        Order arbitration = repository.save(newOrder(5001L, 10L, 20L));
        arbitration.setStatus(OrderStatus.IN_ARBITRATION);
        repository.save(arbitration);
        Order pending = repository.save(newOrder(5002L, 11L, 21L));
        pending.setStatus(OrderStatus.IN_PROGRESS);
        repository.save(pending);
        Order completed = repository.save(newOrder(5003L, 12L, 22L));
        completed.setStatus(OrderStatus.COMPLETED);
        repository.save(completed);

        List<Order> page = repository.findArbitrationPage(1, 20);

        assertThat(page).extracting(Order::getId).containsExactly(arbitration.getId());
        assertThat(repository.countArbitration()).isEqualTo(1L);
    }

    @Test
    void findArbitrationPage_sorts_by_updated_at_desc_then_id_desc() {
        Order older = repository.save(newOrder(5101L, 10L, 20L));
        older.setStatus(OrderStatus.IN_ARBITRATION);
        older.setUpdatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        repository.save(older);
        Order newer = repository.save(newOrder(5102L, 11L, 21L));
        newer.setStatus(OrderStatus.IN_ARBITRATION);
        newer.setUpdatedAt(LocalDateTime.of(2026, 9, 2, 10, 0));
        repository.save(newer);
        Order sameInstant = repository.save(newOrder(5103L, 12L, 22L));
        sameInstant.setStatus(OrderStatus.IN_ARBITRATION);
        sameInstant.setUpdatedAt(newer.getUpdatedAt());
        repository.save(sameInstant);

        List<Order> page = repository.findArbitrationPage(1, 20);

        assertThat(page).hasSize(3);
        assertThat(page.get(0).getId()).isEqualTo(sameInstant.getId());
        assertThat(page.get(1).getId()).isEqualTo(newer.getId());
        assertThat(page.get(2).getId()).isEqualTo(older.getId());
    }

    @Test
    void findArbitrationPage_places_null_updated_at_last() {
        Order withTime = repository.save(newOrder(5201L, 10L, 20L));
        withTime.setStatus(OrderStatus.IN_ARBITRATION);
        withTime.setUpdatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        repository.save(withTime);
        Order nullTime = repository.save(newOrder(5202L, 11L, 21L));
        nullTime.setStatus(OrderStatus.IN_ARBITRATION);
        repository.save(nullTime);

        List<Order> page = repository.findArbitrationPage(1, 20);

        assertThat(page).hasSize(2);
        assertThat(page.get(0).getId()).isEqualTo(withTime.getId());
        assertThat(page.get(1).getId()).isEqualTo(nullTime.getId());
    }

    @Test
    void findArbitrationPage_applies_limit_and_offset_for_pagination() {
        for (int i = 0; i < 5; i++) {
            Order o = repository.save(newOrder(5300L + i, 10L, 20L));
            o.setStatus(OrderStatus.IN_ARBITRATION);
            o.setUpdatedAt(LocalDateTime.now().minusMinutes(5 - i));
            repository.save(o);
        }

        assertThat(repository.findArbitrationPage(1, 2)).hasSize(2);
        assertThat(repository.findArbitrationPage(2, 2)).hasSize(2);
        assertThat(repository.findArbitrationPage(3, 2)).hasSize(1);
        assertThat(repository.countArbitration()).isEqualTo(5L);
    }

    @Test
    void findArbitrationPage_returns_orders_with_empty_status_history() {
        Order arbitration = repository.save(newOrder(5401L, 10L, 20L));
        arbitration.setStatus(OrderStatus.IN_ARBITRATION);
        arbitration.addHistory(null, OrderStatus.ACCEPTED, 20L, "接单", LocalDateTime.now());
        repository.save(arbitration);

        List<Order> page = repository.findArbitrationPage(1, 20);

        assertThat(page).hasSize(1);
        assertThat(page.get(0).getStatusHistory()).isNotNull().isEmpty();
    }

    @Test
    void countArbitration_counts_only_in_arbitration_orders() {
        Order a1 = repository.save(newOrder(5501L, 10L, 20L));
        a1.setStatus(OrderStatus.IN_ARBITRATION);
        repository.save(a1);
        Order a2 = repository.save(newOrder(5502L, 11L, 21L));
        a2.setStatus(OrderStatus.IN_ARBITRATION);
        repository.save(a2);
        Order other = repository.save(newOrder(5503L, 12L, 22L));
        other.setStatus(OrderStatus.COMPLETED);
        repository.save(other);

        assertThat(repository.countArbitration()).isEqualTo(2L);
    }

    @Test
    void countArbitration_matches_findArbitrationPage_total() {
        for (int i = 0; i < 3; i++) {
            Order o = repository.save(newOrder(5600L + i, 10L, 20L));
            o.setStatus(OrderStatus.IN_ARBITRATION);
            repository.save(o);
        }

        assertThat(repository.countArbitration()).isEqualTo(3L);
        assertThat(repository.findArbitrationPage(1, 20)).hasSize(3);
    }

    @Test
    void count_returns_zero_when_empty() {
        assertThat(repository.count()).isZero();
    }

    @Test
    void count_returns_total_after_inserts() {
        repository.save(newOrder(6001L, 10L, 20L));
        repository.save(newOrder(6002L, 11L, 21L));

        assertThat(repository.count()).isEqualTo(2L);
    }

    @Test
    void countByStatus_counts_matching_status_and_handles_null() {
        Order arbitration = repository.save(newOrder(6101L, 10L, 20L));
        arbitration.setStatus(OrderStatus.IN_ARBITRATION);
        repository.save(arbitration);
        repository.save(newOrder(6102L, 11L, 21L)); // 默认 ACCEPTED

        assertThat(repository.countByStatus(OrderStatus.IN_ARBITRATION)).isEqualTo(1L);
        assertThat(repository.countByStatus(OrderStatus.ACCEPTED)).isEqualTo(1L);
        assertThat(repository.countByStatus(null)).isZero();
    }

    @Test
    void findHistoryPage_returns_orders_where_publisher_or_accepter_matches() {
        Order asPublisher = repository.save(newOrder(7001L, 10L, 20L)); // publisherId=10 命中
        Order asAccepter = repository.save(newOrder(7002L, 11L, 10L));  // accepterId=10 命中
        repository.save(newOrder(7003L, 12L, 22L)); // 都不命中

        List<Order> page = repository.findHistoryPage(10L, new OrderHistoryQuery(new PageQuery(1, 20)));

        assertThat(page).hasSize(2);
        assertThat(page).extracting(Order::getDemandId).containsExactlyInAnyOrder(7001L, 7002L);
        assertThat(repository.countHistory(10L)).isEqualTo(2L);
    }

    @Test
    void findHistoryPage_sorts_by_created_at_desc_then_id_desc() {
        Order older = repository.save(newOrder(7101L, 10L, 20L));
        older.setCreatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        repository.save(older);
        Order newer = repository.save(newOrder(7102L, 10L, 21L));
        newer.setCreatedAt(LocalDateTime.of(2026, 9, 2, 10, 0));
        repository.save(newer);
        Order sameInstant = repository.save(newOrder(7103L, 10L, 22L));
        sameInstant.setCreatedAt(newer.getCreatedAt());
        repository.save(sameInstant);

        List<Order> page = repository.findHistoryPage(10L, new OrderHistoryQuery(new PageQuery(1, 20)));

        assertThat(page).hasSize(3);
        assertThat(page.get(0).getId()).isEqualTo(sameInstant.getId());
        assertThat(page.get(1).getId()).isEqualTo(newer.getId());
        assertThat(page.get(2).getId()).isEqualTo(older.getId());
    }

    @Test
    void findHistoryPage_applies_limit_and_offset_for_pagination() {
        for (int i = 0; i < 5; i++) {
            Order o = repository.save(newOrder(7200L + i, 10L, 20L));
            o.setCreatedAt(LocalDateTime.now().minusMinutes(5 - i));
            repository.save(o);
        }

        assertThat(repository.findHistoryPage(10L, new OrderHistoryQuery(new PageQuery(1, 2)))).hasSize(2);
        assertThat(repository.findHistoryPage(10L, new OrderHistoryQuery(new PageQuery(2, 2)))).hasSize(2);
        assertThat(repository.findHistoryPage(10L, new OrderHistoryQuery(new PageQuery(3, 2)))).hasSize(1);
        assertThat(repository.countHistory(10L)).isEqualTo(5L);
    }

    @Test
    void findHistoryPage_returns_orders_with_empty_status_history() {
        Order order = repository.save(newOrder(7301L, 10L, 20L));
        order.addHistory(null, OrderStatus.ACCEPTED, 20L, "接单", LocalDateTime.now());
        repository.save(order);

        List<Order> page = repository.findHistoryPage(10L, new OrderHistoryQuery(new PageQuery(1, 20)));

        assertThat(page).hasSize(1);
        assertThat(page.get(0).getStatusHistory()).isNotNull().isEmpty();
    }

    @Test
    void countHistory_matches_findHistoryPage_total() {
        repository.save(newOrder(7401L, 10L, 20L));
        repository.save(newOrder(7402L, 11L, 10L));
        repository.save(newOrder(7403L, 10L, 21L));

        assertThat(repository.countHistory(10L)).isEqualTo(3L);
        assertThat(repository.findHistoryPage(10L, new OrderHistoryQuery(new PageQuery(1, 20)))).hasSize(3);
    }

    @Test
    void findHistoryPage_and_countHistory_handle_null() {
        assertThat(repository.findHistoryPage(null, new OrderHistoryQuery(new PageQuery(1, 20)))).isEmpty();
        assertThat(repository.findHistoryPage(10L, null)).isEmpty();
        assertThat(repository.countHistory(null)).isZero();
    }

    /**
     * 工厂方法：为所有 NOT NULL 列（demand_id / publisher_id / accepter_id / status）
     * 提供默认值，避免 H2 抛出 NULL not allowed 异常。
     */
    private static Order newOrder(Long demandId, Long publisherId, Long accepterId) {
        Order order = new Order();
        order.setDemandId(demandId);
        order.setPublisherId(publisherId);
        order.setAccepterId(accepterId);
        order.setStatus(OrderStatus.ACCEPTED);
        order.setProofSubmitted(false);
        order.setProofImageCount(0);
        order.setCreatedAt(LocalDateTime.now());
        return order;
    }
}
