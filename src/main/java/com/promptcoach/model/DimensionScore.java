package com.promptcoach.model;

/**
 * 单个维度的得分。
 *
 * @param key      维度标识，前端用来排序/配色
 * @param name     维度中文名
 * @param score    得分
 * @param max      满分
 * @param evidence 命中的证据（引用原文里的关键词）
 * @param hint     没拿满分时该怎么补
 */
public record DimensionScore(String key, String name, int score, int max, String evidence, String hint) {
}