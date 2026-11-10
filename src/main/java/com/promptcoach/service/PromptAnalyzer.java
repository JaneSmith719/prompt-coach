package com.promptcoach.service;

import com.promptcoach.model.Diagnosis;
import com.promptcoach.model.DimensionScore;
import com.promptcoach.support.Signals;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 五维诊断引擎（纯本地规则，不消耗任何 token）。
 *
 * <p>评分模型：</p>
 * <pre>
 *   角色 ROLE        15 分  有没有告诉 AI「你是谁」
 *   任务 TASK        25 分  动作 + 交付物是否明确
 *   背景 CONTEXT     20 分  给谁用、为什么做、现状如何
 *   约束 CONSTRAINT  20 分  字数、风格、边界
 *   格式 OUTPUT      20 分  要一段话、表格还是清单
 * </pre>
 *
 * <p>输出同时给出证据（命中了哪个词）和缺失项，方便前端解释「为什么扣分」。</p>
 */
@Component
public class PromptAnalyzer {

    public static final int MAX_SCORE = 100;

    public Diagnosis analyze(String raw) {
        String text = raw == null ? "" : raw.trim();
        int length = text.length();

        boolean roleHit = Signals.ROLE.matcher(text).find();
        String rolePhrase = Signals.extractRole(text);
        String actionWord = Signals.first(Signals.ACTION, text);
        int contextHits = Signals.count(Signals.CONTEXT, text);
        boolean audienceHit = Signals.AUDIENCE.matcher(text).find();
        int constraintHits = Signals.count(Signals.CONSTRAINT, text);
        int outputHits = Signals.count(Signals.OUTPUT, text);
        int vagueHits = Signals.count(Signals.VAGUE, text);

        List<DimensionScore> dimensions = List.of(
                role(roleHit, rolePhrase, length),
                task(actionWord, length, vagueHits),
                context(contextHits, audienceHit, length),
                constraint(constraintHits),
                output(outputHits));

        int total = dimensions.stream().mapToInt(DimensionScore::score).sum();
        total = Math.min(MAX_SCORE, total);

        List<String> gaps = new ArrayList<>();
        List<String> questions = new ArrayList<>();

        if (length < 12) {
            gaps.add("信息量太少：一句话很难让 AI 一次给对");
            questions.add("能再补一句：这份结果给谁、用来干什么吗？");
        }
        if (dimensions.get(0).score() == 0) {
            gaps.add("缺角色：没说让 AI 用什么专业身份或视角来做");
            questions.add("你希望 AI 以什么身份来做？比如「资深 Java 后端工程师」「高中数学老师」。");
        }
        if (dimensions.get(1).score() < 18) {
            gaps.add("任务不够具体：动词和最终交付物都偏模糊");
            questions.add("最终要交付的是什么？一段文字、一张表格，还是一份能跑的代码？");
        }
        if (dimensions.get(2).score() < 12) {
            gaps.add("缺背景：给谁用、为什么做、现在什么状况都没交代");
            questions.add("谁会用这份结果？你现在卡在哪一步？");
        }
        if (dimensions.get(3).score() < 10) {
            gaps.add("缺约束：字数、风格、边界没提，结果容易跑偏");
            questions.add("有哪些硬限制？字数、篇幅、风格，或者绝对不能出现的内容。");
        }
        if (dimensions.get(4).score() < 12) {
            gaps.add("缺输出格式：不知道要它交一段话、一张表还是一份清单");
            questions.add("输出长什么样最顺手？分点清单、表格，还是 300 字以内的段落。");
        }

        return new Diagnosis(
                total,
                MAX_SCORE,
                grade(total),
                summary(gaps, dimensions),
                dimensions,
                gaps.stream().limit(5).toList(),
                questions.stream().limit(5).toList());
    }

    private DimensionScore role(boolean hit, String phrase, int length) {
        int score = 0;
        String evidence;
        if (hit) {
            score = phrase != null ? 15 : 12;
            evidence = phrase != null ? "已交代身份：" + phrase : "提到了角色/身份相关说法";
        } else {
            evidence = "没看到「你是…」「作为…」这类身份设定";
        }
        return new DimensionScore("role", "角色设定", score, 15, evidence,
                "开头加一句：你是[某领域]的[资深/资深一点的]专家。");
    }

    private DimensionScore task(String actionWord, int length, int vagueHits) {
        int score;
        String evidence;
        if (length <= 3) {
            score = 0;
            evidence = "输入太短，看不出要做什么";
        } else if (actionWord != null && length >= 12) {
            score = 25;
            evidence = "动作明确：" + actionWord + "，且给了足够描述";
        } else if (actionWord != null) {
            score = 18;
            evidence = "动作明确：" + actionWord + "，但描述偏短";
        } else if (length >= 24) {
            score = 10;
            evidence = "有描述，但没有明确的动词（写/总结/分析…）";
        } else {
            score = 4;
            evidence = "没有可执行的动作";
        }
        score = Math.max(0, score - Math.min(6, vagueHits * 2));
        return new DimensionScore("task", "任务明确", score, 25, evidence,
                "把「帮我弄一下」换成「帮我写一份 800 字的周报初稿」。");
    }

    private DimensionScore context(int contextHits, boolean audienceHit, int length) {
        int score = 0;
        List<String> evidence = new ArrayList<>();
        if (contextHits > 0) {
            score += 8;
            evidence.add("提到了背景线索");
        }
        if (length >= 90) {
            score += 6;
            evidence.add("描述比较充分");
        } else if (length >= 45) {
            score += 3;
            evidence.add("描述长度一般");
        }
        if (audienceHit) {
            score += 6;
            evidence.add("说明了给谁看");
        }
        score = Math.min(20, score);
        String text = evidence.isEmpty() ? "没有交代使用场景和受众" : String.join("、", evidence);
        return new DimensionScore("context", "背景上下文", score, 20, text,
                "补一句：我是大三学生，这份周报要发给带我的导师，团队一共 10 人。");
    }

    private DimensionScore constraint(int hits) {
        int score = Math.min(20, hits * 10);
        String evidence = hits == 0 ? "没有字数、风格、边界上的限制" : "出现了 " + hits + " 处约束条件";
        return new DimensionScore("constraint", "约束条件", score, 20, evidence,
                "加约束：不超过 500 字、语气正式、不要出现「赋能」这类黑话。");
    }

    private DimensionScore output(int hits) {
        int score = hits >= 2 ? 20 : hits == 1 ? 12 : 0;
        String evidence = hits == 0 ? "没说输出是什么形态" : "指定了 " + hits + " 处输出形态";
        return new DimensionScore("output", "输出格式", score, 20, evidence,
                "点名格式：用 Markdown 表格输出，三列分别是任务、进展、风险。");
    }

    private String grade(int total) {
        if (total >= 85) {
            return "S";
        }
        if (total >= 70) {
            return "A";
        }
        if (total >= 55) {
            return "B";
        }
        if (total >= 40) {
            return "C";
        }
        return "D";
    }

    private String summary(List<String> gaps, List<DimensionScore> dimensions) {
        if (gaps.isEmpty()) {
            return "五个要素都齐了，这份需求可以直接交给 AI。";
        }
        String weakest = dimensions.stream()
                .min((a, b) -> Integer.compare(a.score() * 100 / a.max(), b.score() * 100 / b.max()))
                .map(DimensionScore::name)
                .orElse("任务明确");
        return "还差 " + gaps.size() + " 项没交代，其中「" + weakest + "」最影响结果质量，建议先补它。";
    }
}