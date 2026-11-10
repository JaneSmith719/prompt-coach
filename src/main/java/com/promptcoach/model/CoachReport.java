package com.promptcoach.model;

import java.time.Instant;
import java.util.List;

/**
 * 一次完整的教练报告。
 *
 * @param coverage      改写后覆盖了几个维度（0-5）
 * @param placeholders  改写稿里仍需要用户自己填的部分
 * @param structuredPrompt 主推的改写稿（LLM 增强成功时是模型产出，否则是本地拼装稿）
 * @param mode          local = 纯本地规则；llm = 大模型增强
 * @param notice        降级/告警提示，正常时为 null
 */
public record CoachReport(
        String id,
        String original,
        Diagnosis diagnosis,
        int coverage,
        List<String> placeholders,
        String structuredPrompt,
        List<PromptVariant> variants,
        List<Advice> advice,
        List<String> nextSteps,
        boolean llmEnhanced,
        String mode,
        String model,
        String notice,
        long elapsedMs,
        Instant createdAt) {
}