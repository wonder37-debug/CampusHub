package com.campushub.backend.order.service;

import com.campushub.backend.auth.repository.UserRepository;
import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@Transactional(rollbackFor = Exception.class)
public class RewardSettlementServiceImpl implements RewardSettlementService {

    private final UserRepository userRepository;

    public RewardSettlementServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void settleToAccepter(Long publisherId, Long accepterId, BigDecimal reward) {
        if (reward == null || reward.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (!userRepository.deductBalance(publisherId, reward)) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "发布者余额不足，无法结算悬赏");
        }
        if (!userRepository.unfreezeBalance(publisherId, reward)) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "冻结金额不足，无法结算悬赏");
        }
        if (!userRepository.addBalance(accepterId, reward)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "接单者不存在，无法结算悬赏");
        }
    }

    @Override
    public void settleToMultipleAccepters(Long publisherId, List<Long> accepterIds, BigDecimal totalReward) {
        if (totalReward == null || totalReward.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (accepterIds == null || accepterIds.isEmpty()) {
            return;
        }
        List<Long> distinctIds = accepterIds.stream().distinct().toList();
        BigDecimal share = totalReward.divide(BigDecimal.valueOf(distinctIds.size()), 2, RoundingMode.DOWN);
        for (int i = 0; i < distinctIds.size(); i++) {
            Long accepterId = distinctIds.get(i);
            BigDecimal amount = share;
            if (i == distinctIds.size() - 1) {
                // 最后一人承担除法余数，保证总和等于 totalReward
                BigDecimal remainder = totalReward.subtract(share.multiply(BigDecimal.valueOf(distinctIds.size())));
                amount = share.add(remainder);
            }
            settleToAccepter(publisherId, accepterId, amount);
        }
    }

    @Override
    public void refundToPublisher(Long publisherId, BigDecimal reward) {
        if (reward == null || reward.compareTo(BigDecimal.ZERO) <= 0 || publisherId == null) {
            return;
        }
        if (!userRepository.unfreezeBalance(publisherId, reward)) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "冻结金额不足，无法解冻悬赏");
        }
    }
}
