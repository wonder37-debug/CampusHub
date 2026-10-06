package com.campushub.backend.order.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 悬赏结算服务，统一封装冻结/解冻/扣减/增加的原子操作。
 *
 * <p>消除原 {@code OrderApplicationServiceImpl.transferReward} 与
 * {@code AdminApplicationServiceImpl.transferReward} 的复制粘贴，
 * 并支持 SELECT_MANY 多人平分与 HELP 单人生结算。
 */
public interface RewardSettlementService {

    /** 把 reward 从 publisher 结算给单个 accepter（deduct + unfreeze + add）。 */
    void settleToAccepter(Long publisherId, Long accepterId, BigDecimal reward);

    /** 把 totalReward 平均分配给多个 accepter，余数分给最后一人。 */
    void settleToMultipleAccepters(Long publisherId, List<Long> accepterIds, BigDecimal totalReward);

    /** 解冻 reward 给 publisher（取消/退款场景）。 */
    void refundToPublisher(Long publisherId, BigDecimal reward);
}
