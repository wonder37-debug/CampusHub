package com.campushub.backend.ai.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campushub.backend.common.exception.BusinessException;
import com.campushub.backend.common.exception.ErrorCode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * AiDemandRateLimiter 单元测试。覆盖 per-user rate limit、并发限制、窗口过期恢复、
 * 空闲 entry 清理、并发安全（不产生 split limiter state）。
 */
class AiDemandRateLimiterTest {

    /** 构造一个可控参数的 limiter。 */
    private AiDemandRateLimiter limiter(long windowMs, int maxPerWindow, int maxConcurrent) {
        return new AiDemandRateLimiter(windowMs, maxPerWindow, maxConcurrent);
    }

    @Test
    void shouldAllowUpToMaxPerWindowThenReject() {
        AiDemandRateLimiter r = limiter(60_000L, 3, 10);
        Long userId = 1L;

        // 前 3 次成功
        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> r.acquire(userId), "第 " + (i + 1) + " 次应成功");
            r.release();
        }
        // 第 4 次拒绝
        BusinessException ex = assertThrows(BusinessException.class, () -> r.acquire(userId));
        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
    }

    @Test
    void shouldRejectWithRateLimitedCode() {
        AiDemandRateLimiter r = limiter(60_000L, 1, 10);
        Long userId = 2L;

        r.acquire(userId);
        r.release();
        BusinessException ex = assertThrows(BusinessException.class, () -> r.acquire(userId));
        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
        // 用户友好提示
        assertTrue(ex.getMessage().contains("频繁") || ex.getMessage().contains("繁忙"));
    }

    @Test
    void shouldRecoverAfterWindowExpires() throws InterruptedException {
        AiDemandRateLimiter r = limiter(150L, 2, 10);
        Long userId = 3L;

        r.acquire(userId);
        r.release();
        r.acquire(userId);
        r.release();
        // 窗口内已达上限
        assertThrows(BusinessException.class, () -> r.acquire(userId));

        // 等待窗口过期
        Thread.sleep(220L);

        // 恢复
        assertDoesNotThrow(() -> r.acquire(userId));
        r.release();
    }

    @Test
    void shouldNotAffectOtherUsers() {
        AiDemandRateLimiter r = limiter(60_000L, 2, 10);
        Long userA = 10L;
        Long userB = 20L;

        // userA 达上限
        r.acquire(userA);
        r.release();
        r.acquire(userA);
        r.release();
        assertThrows(BusinessException.class, () -> r.acquire(userA));

        // userB 不受影响
        assertDoesNotThrow(() -> r.acquire(userB));
        r.release();
    }

    @Test
    void shouldEnforceGlobalConcurrentLimit() {
        AiDemandRateLimiter r = limiter(60_000L, 100, 2);
        Long user = 30L;

        // 占满 2 个并发槽位（不 release）
        r.acquire(user);
        r.acquire(user);

        // 第 3 个并发被拒绝
        BusinessException ex = assertThrows(BusinessException.class, () -> r.acquire(user));
        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("繁忙"));

        // release 一个后恢复
        r.release();
        assertDoesNotThrow(() -> r.acquire(user));
        r.release();
        r.release();
    }

    @Test
    void shouldReleaseConcurrentSlotAfterAcquire() {
        AiDemandRateLimiter r = limiter(60_000L, 100, 1);
        Long user = 40L;

        r.acquire(user);
        // 未 release，并发槽位满
        assertThrows(BusinessException.class, () -> r.acquire(user));
        // release 后恢复
        r.release();
        assertDoesNotThrow(() -> r.acquire(user));
        r.release();
    }

    @Test
    void shouldEvictUserEntryAfterAllTimestampsExpire() throws InterruptedException {
        AiDemandRateLimiter r = limiter(100L, 2, 10);
        Long user = 50L;

        r.acquire(user);
        r.release();
        // 等待窗口过期
        Thread.sleep(150L);

        // 手动触发清理
        r.evictExpiredEntries(System.currentTimeMillis());

        // 清理后，该用户应能重新 acquire（entry 已删除，新窗口）
        assertDoesNotThrow(() -> r.acquire(user));
        r.release();
    }

    @Test
    void shouldEvictWithStableProgressAcrossBatches() throws InterruptedException {
        // 200 个用户，单次清理批量 64，验证多次 evict 能持续推进覆盖所有 entry（不反复处理同一小批）
        // 注意：acquire 路径有 1/100 概率触发 evict，cursor 可能被更新到中间位置，
        //       因此不依赖第一次 evict 的具体数量，而是验证 activeUserCount 持续减少最终为 0
        AiDemandRateLimiter r = limiter(100L, 5, 300);
        for (long uid = 1; uid <= 200; uid++) {
            r.acquire(uid);
            r.release();
        }
        assertEquals(200, r.activeUserCount());

        // 等待窗口过期
        Thread.sleep(150L);
        long now = System.currentTimeMillis();

        // 多次 evict：activeUserCount 持续减少（或不增）最终为 0
        // 这验证稳定 progress——每次 evict 清理不同 batch，不反复处理同一小批
        int prevCount = 200;
        for (int i = 0; i < 8 && r.activeUserCount() > 0; i++) {
            r.evictExpiredEntries(now);
            int currentCount = r.activeUserCount();
            assertTrue(currentCount <= prevCount,
                "activeUserCount 应持续减少或不增，第 " + i + " 次 evict 后=" + currentCount + " prev=" + prevCount);
            prevCount = currentCount;
        }
        assertEquals(0, r.activeUserCount(), "多次 evict 后应全部清理（稳定 progress，不反复处理同一小批）");

        // 空 map 再 evict 不报错（游标重置）
        r.evictExpiredEntries(now);
        assertEquals(0, r.activeUserCount());
    }

    @Test
    void shouldNotDeleteEntryForUserWithinWindowDuringEvict() throws InterruptedException {
        // 用户在窗口内有未过期时间戳时，evict 不应删除其 entry
        AiDemandRateLimiter r = limiter(60_000L, 5, 10);
        Long user = 70L;

        r.acquire(user);
        r.release();
        // 未等窗口过期，直接 evict
        r.evictExpiredEntries(System.currentTimeMillis());

        // entry 仍存在（时间戳未过期），用户不能突破限流
        // acquire 4 次成功（共 5 次，含第一次），第 6 次拒绝
        for (int i = 0; i < 4; i++) {
            assertDoesNotThrow(() -> r.acquire(user));
            r.release();
        }
        BusinessException ex = assertThrows(BusinessException.class, () -> r.acquire(user));
        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
    }

    @Test
    void shouldNotProduceSplitLimiterStateUnderConcurrentAcquire() throws InterruptedException {
        // 高并发同 userId acquire，验证不会因 map entry 清理造成两个独立 list（绕过限流）
        AiDemandRateLimiter r = limiter(60_000L, 5, 50);
        Long user = 60L;

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    try {
                        r.acquire(user);
                        accepted.incrementAndGet();
                        // 不立即 release，让并发槽位被占用（maxConcurrent=50 足够所有线程）
                    } catch (BusinessException ex) {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        // maxPerWindow=5，所以最多 5 个 accept，其余 reject
        // 关键断言：accept 不应超过 maxPerWindow（如果产生 split state，会有两个独立 list，accept 可能 > 5）
        assertTrue(accepted.get() <= 5,
            "accepted=" + accepted.get() + " 超过 maxPerWindow=5，存在 split limiter state");
        assertEquals(5, accepted.get(),
            "应有恰好 5 个 accept，实际 " + accepted.get());
        assertEquals(threads - 5, rejected.get());
    }
}
