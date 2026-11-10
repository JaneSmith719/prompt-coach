package com.promptcoach.model;

/**
 * 改写后的提示词版本。
 *
 * @param label       版本名：结构化版 / 极简版 / 反问版
 * @param description 这个版本适合什么场景
 */
public record PromptVariant(String label, String description, String prompt) {
}