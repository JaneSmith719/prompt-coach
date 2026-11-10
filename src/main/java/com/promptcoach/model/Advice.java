package com.promptcoach.model;

/**
 * 一条提效建议。
 *
 * @param level 优先级：高 / 中，前端据此上色
 */
public record Advice(String title, String detail, String level) {
}