package com.promptcoach.service;

import com.promptcoach.config.CoachProperties;
import com.promptcoach.model.HistoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryStoreTest {

    private CoachProperties properties;
    private HistoryStore store;

    @BeforeEach
    void setUp() {
        properties = new CoachProperties();
        properties.setHistoryLimit(2);
        properties.setMaxSessions(2);
        store = new HistoryStore(properties);
    }

    @Test
    @DisplayName("不同会话的历史互不可见")
    void isolatesSessions() {
        store.add("a", entry("a1"));
        store.add("b", entry("b1"));

        assertThat(store.recent("a", 10)).extracting(HistoryEntry::text).containsExactly("a1");
        assertThat(store.recent("b", 10)).extracting(HistoryEntry::text).containsExactly("b1");
        assertThat(store.recent("c", 10)).isEmpty();
    }

    @Test
    @DisplayName("单个会话只保留最近的 N 条")
    void limitsPerSession() {
        store.add("a", entry("a1"));
        store.add("a", entry("a2"));
        store.add("a", entry("a3"));

        assertThat(store.recent("a", 10)).extracting(HistoryEntry::text)
                .containsExactly("a3", "a2");
    }

    @Test
    @DisplayName("清空只影响当前会话")
    void clearsOnlyRequestedSession() {
        store.add("a", entry("a1"));
        store.add("b", entry("b1"));

        store.clear("a");

        assertThat(store.recent("a", 10)).isEmpty();
        assertThat(store.recent("b", 10)).hasSize(1);
    }

    @Test
    @DisplayName("会话数超过上限时淘汰最久未访问的会话")
    void evictsLeastRecentlyUsedSession() {
        store.add("a", entry("a1"));
        store.add("b", entry("b1"));
        // 访问 a，让 b 变成最久未使用
        assertThat(store.recent("a", 10)).hasSize(1);

        store.add("c", entry("c1"));

        assertThat(store.sessionCount()).isEqualTo(2);
        assertThat(store.recent("b", 10)).isEmpty();
        assertThat(store.recent("a", 10)).hasSize(1);
        assertThat(store.recent("c", 10)).hasSize(1);
    }

    private static HistoryEntry entry(String text) {
        return new HistoryEntry("id-" + text, text, 10, "D", false, Instant.now());
    }
}