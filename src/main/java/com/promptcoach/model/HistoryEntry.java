package com.promptcoach.model;

import java.time.Instant;

/**
 * 历史记录条目（内存保存，重启即清空，够用且零依赖）。
 */
public record HistoryEntry(
        String id,
        String text,
        int score,
        String grade,
        boolean llmEnhanced,
        Instant createdAt) {
}