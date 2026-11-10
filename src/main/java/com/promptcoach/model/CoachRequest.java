package com.promptcoach.model;

/**
 * 诊断入参。
 *
 * @param text       用户原始需求
 * @param enhance    是否请求大模型增强（未配置 Key 时会被忽略）
 * @param templateId 可选：从模板库带出的场景 id
 */
public record CoachRequest(String text, Boolean enhance, String templateId) {
}