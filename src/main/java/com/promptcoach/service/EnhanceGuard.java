package com.promptcoach.service;

import com.promptcoach.config.CoachProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * 大模型增强的配额闸门。
 *
 * <p>公开实例一旦配上 {@code AI_API_KEY}，任何人都能刷增强接口。
 * 这里用三层限制兜底：</p>
 * <ul>
 *   <li>单会话每小时次数：保证普通用户不会被别人挤掉；</li>
 *   <li>全站每小时次数：真正的成本上限；</li>
 *   <li>全站并发数：避免瞬间打满连接池/上游限流。</li>
 * </ul>
 *
 * <p>被拒绝时不会调用大模型，由 {@link CoachService} 降级成本地规则结果，
 * 所以用户看到的仍然是一份完整报告，而不是 429/500。</p>
 */
@Component
public class EnhanceGuard {

    private static final long HOUR_MS = 60 * 60 * 1000L;
    private static final String ANONYMOUS = "anonymous";

    private final CoachProperties.Enhance config;
    private final Clock clock;
    private final int maxTrackedSessions;
    private final Semaphore permits;

    /** accessOrder=true：会话数超过上限时淘汰最久未请求的会话 */
    private final Map<String, Window> windows = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
            return size() > maxTrackedSessions;
        }
    };

    private long globalWindowStart = -1;
    private int globalCount;

    public EnhanceGuard(CoachProperties properties, Clock clock) {
        this.config = properties.getEnhance();
        this.clock = clock;
        this.maxTrackedSessions = Math.max(1, properties.getMaxSessions());
        this.permits = config.getMaxConcurrent() > 0 ? new Semaphore(config.getMaxConcurrent()) : null;
    }

    /**
     * 尝试占用一次增强配额。
     *
     * @return 被拒绝时 {@code allowed=false}；允许时如果持有并发许可，
     *         {@code leased=true}，调用方必须在 finally 里调用 {@link #release()}。
     */
    public synchronized Decision tryAcquire(String sessionId) {
        if (!config.isEnabled()) {
            return Decision.allowed(false);
        }

        long now = clock.millis();
        rollGlobalWindow(now);
        if (config.getGlobalPerHour() > 0 && globalCount >= config.getGlobalPerHour()) {
            return Decision.denied(Reason.GLOBAL_LIMIT);
        }

        Window window = windows.computeIfAbsent(key(sessionId), ignored -> new Window());
        rollWindow(window, now);
        if (config.getPerSessionPerHour() > 0 && window.count >= config.getPerSessionPerHour()) {
            return Decision.denied(Reason.SESSION_LIMIT);
        }

        boolean leased = false;
        if (permits != null) {
            if (!permits.tryAcquire()) {
                return Decision.denied(Reason.BUSY);
            }
            leased = true;
        }

        window.count++;
        globalCount++;
        return Decision.allowed(leased);
    }

    /** 只释放并发许可；次数配额按整点窗口自然重置 */
    public void release() {
        if (permits != null) {
            permits.release();
        }
    }

    public int perHour() {
        return config.getPerSessionPerHour();
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    private void rollGlobalWindow(long now) {
        long start = now - Math.floorMod(now, HOUR_MS);
        if (start != globalWindowStart) {
            globalWindowStart = start;
            globalCount = 0;
        }
    }

    private void rollWindow(Window window, long now) {
        long start = now - Math.floorMod(now, HOUR_MS);
        if (start != window.windowStart) {
            window.windowStart = start;
            window.count = 0;
        }
    }

    private static String key(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? ANONYMOUS : sessionId;
    }

    private static final class Window {
        private long windowStart = -1;
        private int count;
    }

    public enum Reason {
        SESSION_LIMIT,
        GLOBAL_LIMIT,
        BUSY
    }

    public record Decision(boolean allowed, boolean leased, Reason reason) {

        static Decision allowed(boolean leased) {
            return new Decision(true, leased, null);
        }

        static Decision denied(Reason reason) {
            return new Decision(false, false, reason);
        }
    }
}