package com.campushub.backend.order.repository;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.campushub.backend.order.domain.Order;
import com.campushub.backend.order.domain.OrderStatus;
import com.campushub.backend.order.domain.OrderStatusHistoryEntry;
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
