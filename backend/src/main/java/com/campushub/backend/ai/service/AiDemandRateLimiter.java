package com.campushub.backend.ai.service;

import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI 草稿生成接口的轻量限流器（内存级，单实例）。
 *
 * <p>两层限制：
 * <ul>
 *   <li><b>per-user rate limit</b>：滑动窗口内每用户最多 N 次请求（默认 1 分钟 5 次）。</li>
 *   <li><b>concurrent request limit</b>：全局最多 M 个并发请求（默认 3），避免 AI 调用打满线程池。</li>
 * </ul>
 *
 * <p><b>单实例限流</b>：状态保存在进程内存（{@link ConcurrentHashMap} + {@link Semaphore}），
 * 不依赖 Redis。多实例部署时每实例独立计数，实际限额 = 实例数 × 配置值。
 * 后续可替换为 Redis + Bucket4j 等分布式限流方案，只需实现相同接口契约。
 *
 * <p>安全要求：
 * <ul>
 *   <li>不记录 JWT / Authorization header，只用 userId 计数。</li>
 *   <li>不把用户身份信息传给 LLM。</li>
 *   <li>admin 用户仍遵守登录权限（admin 无法发布需求，但可调用 AI 草稿生成接口生成草稿）。</li>
 * </ul>
 */
@Component
public class AiDemandRateLimiter {

    private final ConcurrentHashMap<Long, LinkedList<Long>> userRequests = new ConcurrentHashMap<>();
    private final Semaphore concurrentSlots;
    private final long windowMs;
    private final int maxRequestsPerWindow;

    public AiDemandRateLimiter(
        @Value("${app.ai.demand-draft.rate-limit.window-ms:60000}") long windowMs,
        @Value("${app.ai.demand-draft.rate-limit.max-per-window:5}") int maxRequestsPerWindow,
        @Value("${app.ai.demand-draft.rate-limit.max-concurrent:3}") int maxConcurrent
    ) {
        this.windowMs = windowMs;
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.concurrentSlots = new Semaphore(maxConcurrent, true);
    }

    /**
     * 获取调用配额。成功后调用方必须在 finally 中调用 {@link #release()} 释放并发 slot。
     * rate limit 配额即使后续 AI 调用失败也不回滚（失败的请求也消耗配额，避免失败重试风暴）。
     */
    public void acquire(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请先登录后再使用 AI 帮我发布");
        }
        // 1. 并发限制（非阻塞，立即失败）
        if (!concurrentSlots.tryAcquire()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "AI 服务繁忙，请稍后重试");
        }
        // 2. per-user rate limit
        if (!tryAcquireUserSlot(userId)) {
            concurrentSlots.release();
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求过于频繁，请稍后再试");
        }
    }

    /**
     * 释放并发 slot。必须在 acquire 成功后的 finally 块中调用。
     */
    public void release() {
        concurrentSlots.release();
    }

    private boolean tryAcquireUserSlot(Long userId) {
        long now = System.currentTimeMillis();
        LinkedList<Long> timestamps = userRequests.computeIfAbsent(userId, k -> new LinkedList<>());
        synchronized (timestamps) {
            // 移除窗口外的时间戳
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMs) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxRequestsPerWindow) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }
}
