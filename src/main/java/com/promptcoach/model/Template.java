package com.promptcoach.model;

import java.util.List;

/**
 * 场景模板。点一下就能带出示例输入和可套用的骨架。
 */
public record Template(
        String id,
        String name,
        String category,
        String intent,
        String example,
        String skeleton,
        List<String> tips) {
}