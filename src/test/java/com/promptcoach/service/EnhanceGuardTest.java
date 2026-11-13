package com.promptcoach.service;

import com.promptcoach.config.CoachProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class EnhanceGuardTest {

    private CoachProperties properties;
    private MutableClock clock;
    private EnhanceGuard guard;

    @BeforeEach
    void setUp() {
        properties = new CoachProperties();
        properties.setMaxSessions(10);
        properties.getEnhance().setPerSessionPerHour(2);
        properties.getEnhance().setGlobalPerHour(0);
        properties.getEnhance().setMaxConcurrent(0);
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        guard = new EnhanceGuard(properties, clock);
    }

    @Test
    @DisplayName("单会话达到每小时上限后被拒绝")
    void rejectsAfterPerSessionLimit() {
        assertThat(guard.tryAcquire("a").allowed()).isTrue();
        assertThat(guard.tryAcquire("a").allowed()).isTrue();
        EnhanceGuard.Decision denied = guard.tryAcquire("a");

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reason()).isEqualTo(EnhanceGuard.Reason.SESSION_LIMIT);
        // 另一个会话不受影响
        assertThat(guard.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    @DisplayName("进入下一个整点窗口后次数重置")
    void resetsAfterHour() {
        guard.tryAcquire("a");
        guard.tryAcquire("a");
        assertThat(guard.tryAcquire("a").allowed()).isFalse();

        clock.advance(Duration.ofHours(1));

        assertThat(guard.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    @DisplayName("全站每小时上限优先于单会话上限")
    void enforcesGlobalLimit() {
        properties.getEnhance().setPerSessionPerHour(100);
        properties.getEnhance().setGlobalPerHour(2);
        guard = new EnhanceGuard(properties, clock);

        assertThat(guard.tryAcquire("a").allowed()).isTrue();
        assertThat(guard.tryAcquire("b").allowed()).isTrue();
        EnhanceGuard.Decision denied = guard.tryAcquire("c");

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reason()).isEqualTo(EnhanceGuard.Reason.GLOBAL_LIMIT);
    }

    @Test
    @DisplayName("并发许可耗尽时快速失败，释放后恢复")
    void enforcesConcurrency() {
        properties.getEnhance().setPerSessionPerHour(100);
        properties.getEnhance().setMaxConcurrent(1);
        guard = new EnhanceGuard(properties, clock);

        EnhanceGuard.Decision first = guard.tryAcquire("a");
        assertThat(first.allowed()).isTrue();
        assertThat(first.leased()).isTrue();

        EnhanceGuard.Decision busy = guard.tryAcquire("b");
        assertThat(busy.allowed()).isFalse();
        assertThat(busy.reason()).isEqualTo(EnhanceGuard.Reason.BUSY);

        guard.release();
        assertThat(guard.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    @DisplayName("关闭限流后全部放行，且不占用并发许可")
    void disabledGuardAllowsEverything() {
        properties.getEnhance().setEnabled(false);
        guard = new EnhanceGuard(properties, clock);

        for (int i = 0; i < 20; i++) {
            EnhanceGuard.Decision decision = guard.tryAcquire("a");
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.leased()).isFalse();
        }
        guard.release();
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}