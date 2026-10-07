package com.campushub.backend.ai.service;

import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
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
 * <p><b>并发正确性</b>：单个 userId 的"获取/检查窗口/添加时间戳/判断是否过期/删除 entry"
 * 全部在 {@link ConcurrentHashMap#compute} 内原子完成，不会出现 split state
 * （线程 A 拿旧 list、线程 B 删 entry、线程 C 创建新 list 导致同 userId 两个独立 state）。
 *
 * <p><b>空闲清理</b>：不引入后台清理线程。acquire 时按 1/100 概率顺便清理过期 entry
 * （单次最多扫描 64 个 entry，O(batch) 而非 O(N)，避免大规模用户时阻塞请求路径；
 * ConcurrentHashMap 迭代顺序不保证，多次触发可渐进清理所有过期 entry）。清理规则：
 * 只有当该用户的所有 request timestamps 都已经离开当前 window，才删除该 userId entry。
 * 每个 entry 的清理在 compute 内原子完成，不产生 split state。
 *
 * <p>安全要求：不记录 JWT / Authorization header，只用 userId 计数；不把用户身份信息传给 LLM。
 */
@Component
public class AiDemandRateLimiter {

    private static final int EVICT_PROBABILITY = 100;
    /** 单次清理最多扫描的 entry 数，避免大规模用户时 O(N) 阻塞请求路径。 */
    private static final int MAX_EVICT_BATCH = 64;

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
     *
     * @throws BusinessException RATE_LIMITED 当并发槽位耗尽或用户频率超限
     */
    public void acquire(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请先登录后再使用 AI 帮我发布");
        }
        long now = System.currentTimeMillis();
        // 顺便清理空闲用户的过期 entry（1/100 概率，请求路径触发，非后台线程）
        if (ThreadLocalRandom.current().nextInt(EVICT_PROBABILITY) == 0) {
            evictExpiredEntries(now);
        }
        // 1. 并发限制（非阻塞，立即失败）
        if (!concurrentSlots.tryAcquire()) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "AI 服务繁忙，请稍后重试");
        }
        // 2. per-user rate limit（compute 原子操作，避免 split state）
        if (!tryAcquireUserSlot(userId, now)) {
            concurrentSlots.release();
            throw new BusinessException(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后再试");
        }
    }

    /**
     * 释放并发 slot。必须在 acquire 成功后的 finally 块中调用。
     */
    public void release() {
        concurrentSlots.release();
    }

    /**
     * 单个 userId 的"移除过期时间戳/检查窗口/添加时间戳/判断是否过期/删除 entry"全部在 compute 内原子完成。
     * 返回 null 则删除 entry（仅当所有时间戳过期且本次拒绝——实际不会发生，因为清空后 size=0 < max 必 accept）。
     */
    private boolean tryAcquireUserSlot(Long userId, long now) {
        boolean[] accepted = {false};
        userRequests.compute(userId, (key, existing) -> {
            LinkedList<Long> timestamps = (existing != null) ? existing : new LinkedList<>();
            // 移除窗口外的时间戳
            timestamps.removeIf(ts -> now - ts > windowMs);
            if (timestamps.size() >= maxRequestsPerWindow) {
                // 拒绝：保留 list（非空，有 max 个未过期时间戳）
                accepted[0] = false;
                return timestamps;
            }
            // 接受：添加本次时间戳
            timestamps.addLast(now);
            accepted[0] = true;
            return timestamps;
        });
        return accepted[0];
    }

    /**
     * 清理空闲用户的过期 entry。遍历用 forEach（弱一致），每个 entry 用 compute 原子清理。
     * 清理规则：移除窗口外时间戳后，若 list 为空则删除 entry。
     *
     * <p><b>扫描规模限制</b>：单次最多扫描 {@link #MAX_EVICT_BATCH} 个 entry（O(batch) 而非 O(N)），
     * 避免大规模用户时阻塞请求路径。ConcurrentHashMap 迭代顺序不保证，每次取不同子集，
     * 多次触发可渐进清理所有过期 entry。每个 entry 的 compute 仍是原子的，不产生 split state。
     * package-private 便于单元测试直接调用。
     */
    void evictExpiredEntries(long now) {
        userRequests.entrySet().stream()
            .limit(MAX_EVICT_BATCH)
            .forEach(entry ->
                userRequests.compute(entry.getKey(), (key, existing) -> {
                    if (existing == null) {
                        return null;
                    }
                    existing.removeIf(ts -> now - ts > windowMs);
                    return existing.isEmpty() ? null : existing;
                })
            );
    }
}
