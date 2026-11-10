package com.promptcoach.service;

import com.promptcoach.config.CoachProperties;
import com.promptcoach.model.HistoryEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 最近诊断记录（内存环形队列）。
 *
 * <p>刻意不引数据库：这个工具的价值在诊断逻辑，不在持久化。
 * 想升级成 H2 / MySQL 时，只需要把 add/recent/clear 换成 repository 调用，
 * 其他代码一行都不用改。</p>
 */
@Component
public class HistoryStore {

    private final int limit;
    private final Deque<HistoryEntry> entries = new ArrayDeque<>();

    public HistoryStore(CoachProperties properties) {
        this.limit = Math.max(1, properties.getHistoryLimit());
    }

    public synchronized void add(HistoryEntry entry) {
        entries.addFirst(entry);
        while (entries.size() > limit) {
            entries.removeLast();
        }
    }

    public synchronized List<HistoryEntry> recent(int max) {
        int size = Math.min(Math.max(0, max), entries.size());
        return entries.stream().limit(size).toList();
    }

    public synchronized void clear() {
        entries.clear();
    }
}