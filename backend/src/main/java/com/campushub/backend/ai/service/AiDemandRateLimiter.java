package com.campushub.backend.ai.service;

import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
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
 * <p><b>数据结构选择（关键）</b>：
 * <ul>
 *   <li>rate limit 状态用 {@link ConcurrentHashMap}（{@code compute} 保证 remappingFunction 只执行一次，
 *       避免并发 acquire 时 {@code addLast} 重复执行导致限流突破）。</li>
 *   <li>清理游标用 {@link ConcurrentSkipListSet}（按 userId 升序，支持 {@code tailSet} 轮转游标）。
 *       不用 {@code ConcurrentSkipListMap} 存 rate limit 状态，因为其 {@code compute} 在 CAS 重试时
 *       可能多次执行 remappingFunction，导致限流失效。</li>
 * </ul>
 *
 * <p><b>并发正确性</b>：单个 userId 的"获取/检查窗口/添加时间戳/判断是否过期/删除 entry/userOrder 注册/移除"
 * 全部在 {@link ConcurrentHashMap#compute} 内原子完成，不会出现 split state。
 * <b>userRequests 与 userOrder 一致性</b>：userOrder.add（acquire 时）和 userOrder.remove（evict 删除时）
 * 都在对应 compute 的 remapping function 内执行，保证 entry 创建/保留时 userOrder 同步注册、entry 删除时
 * userOrder 同步移除，不会出现 "userRequests 有 entry 但 userOrder 没有" 或反过来的不一致。
 * 清理与并发 acquire 操作同一 userId 时，{@code compute} 互斥等待，不会误删正在使用的 limiter state。
 *
 * <p><b>空闲清理（轮转游标）</b>：不引入后台清理线程。acquire 时按 1/100 概率触发清理。
 * 使用 {@link ConcurrentSkipListSet}（按 userId 升序）+ {@link AtomicLong} 游标，每次从游标之后
 * 扫描固定批量（64 个 entry），扫描完更新游标；到末尾后重置游标从头开始。这保证清理有稳定
 * progress——随着请求持续执行，所有 entry 最终都会被覆盖到，不会反复处理同一小批。
 * 单次清理 O(batch) 而非 O(N)，不阻塞请求路径。
 *
 * <p>安全要求：不记录 JWT / Authorization header，只用 userId 计数；不把用户身份信息传给 LLM。
 */
@Component
public class AiDemandRateLimiter {

    private static final int EVICT_PROBABILITY = 100;
    /** 单次清理最多扫描的 entry 数，避免大规模用户时 O(N) 阻塞请求路径。 */
    private static final int MAX_EVICT_BATCH = 64;
    /** 游标初始值（小于任何有效 userId），保证首次清理从头开始。 */
    private static final long CURSOR_RESET = Long.MIN_VALUE;

    /** rate limit 状态：userId → 滑动窗口时间戳列表。ConcurrentHashMap.compute 保证 remappingFunction 只执行一次。 */
    private final ConcurrentHashMap<Long, LinkedList<Long>> userRequests = new ConcurrentHashMap<>();
    /** 活跃 userId 有序集合，用于清理游标轮转（ConcurrentSkipListSet 按自然顺序排序，支持 tailSet）。 */
    private final ConcurrentSkipListSet<Long> userOrder = new ConcurrentSkipListSet<>();
    private final AtomicLong evictCursor = new AtomicLong(CURSOR_RESET);
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
        // 2. per-user rate limit（ConcurrentHashMap.compute 原子操作，remappingFunction 只执行一次）
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
     * 单个 userId 的"移除过期时间戳/检查窗口/添加时间戳/判断是否过期/删除 entry/userOrder 注册"
     * 全部在 compute 内原子完成。ConcurrentHashMap.compute 保证 remappingFunction 只执行一次
     * （不会因 CAS 重试导致 addLast 重复），且对同一 userId 互斥，与 evict 的 compute 串行。
     *
     * <p><b>userOrder 同步</b>：userOrder.add 在 compute 临界区内执行，保证 entry 创建/保留时
     * userOrder 同步注册，不会出现 "userRequests 有 entry 但 userOrder 没有该 userId" 的不一致。
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
                // entry 保留，在 compute 临界区内同步 userOrder 注册
                userOrder.add(userId);
                return timestamps;
            }
            // 接受：添加本次时间戳
            timestamps.addLast(now);
            accepted[0] = true;
            // entry 创建/保留，在 compute 临界区内同步 userOrder 注册
            userOrder.add(userId);
            return timestamps;
        });
        return accepted[0];
    }

    /**
     * 清理空闲用户的过期 entry（轮转游标方案）。
     *
     * <p>从游标之后按 userId 升序扫描 {@link #userOrder}，最多 {@link #MAX_EVICT_BATCH} 个，
     * 先收集 key 快照（避免迭代时 compute 修改导致视图异常），再逐个用 ConcurrentHashMap.compute 原子清理。
     * 扫到批量上限则更新游标到最后扫描的 key（下次从这里之后继续）；扫到末尾则重置游标从头开始。
     * 这保证清理有稳定 progress，所有 entry 最终都会被覆盖到。
     *
     * <p>清理规则：移除窗口外时间戳后，若 list 为空则删除 entry（只有当用户的所有 timestamps
     * 都已离开 window 才删除）。用户正在并发 acquire 时，compute 互斥等待其完成，
     * 看到非空 list（含刚加的时间戳）不会删除，不会误删正在使用的 limiter state。
     * package-private 便于单元测试直接调用。
     */
    void evictExpiredEntries(long now) {
        long cursorValue = evictCursor.get();
        // 先收集要扫描的 userId 快照（从游标之后按升序取 MAX_EVICT_BATCH 个）
        List<Long> keysToScan = new ArrayList<>(MAX_EVICT_BATCH);
        for (Long userId : userOrder.tailSet(cursorValue, false)) {
            keysToScan.add(userId);
            if (keysToScan.size() >= MAX_EVICT_BATCH) {
                break;
            }
        }
        if (keysToScan.isEmpty()) {
            // 到末尾，重置游标从头开始
            evictCursor.set(CURSOR_RESET);
            return;
        }
        // 逐个 compute 清理（ConcurrentHashMap.compute 原子，remappingFunction 只执行一次）
        // userOrder.remove 在 compute 临界区内同步执行，保证 entry 删除与 userOrder 移除一致，
        // 不会出现 "userRequests 已删 entry 但 userOrder 仍保留 userId" 的不一致
        for (Long userId : keysToScan) {
            userRequests.compute(userId, (key, existing) -> {
                if (existing == null) {
                    // entry 已不存在，同步从 userOrder 移除
                    userOrder.remove(userId);
                    return null;
                }
                existing.removeIf(ts -> now - ts > windowMs);
                if (existing.isEmpty()) {
                    // 所有时间戳过期，删除 entry，同步从 userOrder 移除
                    userOrder.remove(userId);
                    return null;
                }
                return existing;
            });
        }
        Long lastScanned = keysToScan.get(keysToScan.size() - 1);
        if (keysToScan.size() < MAX_EVICT_BATCH) {
            // 扫到末尾（不足一个批量），重置游标从头开始
            evictCursor.set(CURSOR_RESET);
        } else {
            // 游标设到最后扫描的 key，下次从该 key 之后继续，保证稳定 progress 且不跳过 entry
            evictCursor.set(lastScanned);
        }
    }

    /**
     * 当前活跃用户 entry 数（package-private，仅供单元测试验证清理 progress）。
     */
    int activeUserCount() {
        return userRequests.size();
    }

    /**
     * 验证 userRequests 与 userOrder 对某 userId 的状态一致性（package-private，仅供单元测试）。
     * 一致意味着：userRequests 包含 userId 当且仅当 userOrder 包含 userId。
     * 由于 userOrder.add/remove 都在对应 compute 临界区内同步执行，该一致性应始终成立。
     */
    boolean isUserStateConsistent(Long userId) {
        return userRequests.containsKey(userId) == userOrder.contains(userId);
    }
}
