package com.promptcoach.service;

import com.promptcoach.config.CoachProperties;
import com.promptcoach.model.HistoryEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按浏览器会话隔离的最近诊断记录。
 *
 * <p>v1.0 是一个全局队列，公开部署时任何访客都能看到别人的输入；
 * v1.1 改为以 {@code HttpSession} id 为 key，每个会话只看到自己的记录。
 * 仍然刻意不引数据库：历史只是“最近几条”的便利功能，重启即清空。</p>
 *
 * <p>会话数超过 {@code coach.max-sessions} 时按 LRU 淘汰最久未访问的会话，
 * 防止公开实例被大量匿名会话撑爆内存。</p>
 */
@Component
public class HistoryStore {

    private static final String ANONYMOUS = "anonymous";

    private final int perSessionLimit;
    private final int maxSessions;

    /** accessOrder=true：recent() 也会刷新会话的最近使用顺序 */
    private final Map<String, Deque<HistoryEntry>> bySession =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<HistoryEntry>> eldest) {
                    return size() > maxSessions;
                }
            };

    public HistoryStore(CoachProperties properties) {
        this.perSessionLimit = Math.max(1, properties.getHistoryLimit());
        this.maxSessions = Math.max(1, properties.getMaxSessions());
    }

    public synchronized void add(String sessionId, HistoryEntry entry) {
        Deque<HistoryEntry> entries = bySession.computeIfAbsent(key(sessionId), ignored -> new ArrayDeque<>());
        entries.addFirst(entry);
        while (entries.size() > perSessionLimit) {
            entries.removeLast();
        }
    }

    public synchronized List<HistoryEntry> recent(String sessionId, int max) {
        if (max <= 0) {
            return List.of();
        }
        Deque<HistoryEntry> entries = bySession.get(key(sessionId));
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        int size = Math.min(max, entries.size());
        return entries.stream().limit(size).toList();
    }

    public synchronized void clear(String sessionId) {
        bySession.remove(key(sessionId));
    }

    /** 仅用于测试和观察内存占用 */
    public synchronized int sessionCount() {
        return bySession.size();
    }

    private static String key(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? ANONYMOUS : sessionId;
    }
}