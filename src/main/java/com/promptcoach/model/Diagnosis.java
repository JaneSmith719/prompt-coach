package com.promptcoach.model;

import java.util.List;

/**
 * 五维诊断结果。总分 = 各维度得分之和，满分 100。
 *
 * @param summary 一句话结论，例如「目标有了，但没说给谁看、要多少字」
 */
public record Diagnosis(
        int score,
        int maxScore,
        String grade,
        String summary,
        List<DimensionScore> dimensions,
        List<String> gaps,
        List<String> questions) {
}