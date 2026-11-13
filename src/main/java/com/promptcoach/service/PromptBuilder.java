package com.promptcoach.service;

import com.promptcoach.model.Diagnosis;
import com.promptcoach.model.DimensionScore;
import com.promptcoach.model.Template;
import com.promptcoach.support.Signals;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示词改写器。
 *
 * <p>核心设计：不替你编内容，只把「缺口」变成一眼能看到的占位符
 * {@code 『待补充：…』}。你补一句就能直接发给 AI，而不是拿到一份
 * 看起来很完整、其实全是编的提示词。</p>
 */
@Component
public class PromptBuilder {

    /** 占位符统一标记，前端和测试都靠它识别未完成的部分 */
    public static final String TODO_OPEN = "『待补充：";

    public BuiltPrompts build(String rawText, Diagnosis diagnosis) {
        String text = rawText == null ? "" : rawText.trim();
        boolean roleMissing = scoreOf(diagnosis, "role") == 0;
        boolean taskWeak = scoreOf(diagnosis, "task") < 18;
        boolean contextMissing = scoreOf(diagnosis, "context") < 12;
        boolean constraintMissing = scoreOf(diagnosis, "constraint") < 10;
        boolean outputMissing = scoreOf(diagnosis, "output") < 12;

        String role = roleMissing ? null : Signals.extractRole(text);
        if (!roleMissing && role == null) {
            // 命中「专家/工程师」等泛指词但抽不出完整短语，给一个可改的默认值
            role = "该领域的资深从业者";
        }

        List<String> placeholders = new ArrayList<>();
        if (role == null) {
            placeholders.add("角色身份");
        }
        if (taskWeak) {
            placeholders.add("具体交付物");
        }
        if (contextMissing) {
            placeholders.add("背景与受众");
        }
        if (constraintMissing) {
            placeholders.add("约束条件");
        }
        if (outputMissing) {
            placeholders.add("输出格式");
        }

        return new BuiltPrompts(
                complete(text, role, taskWeak, contextMissing, constraintMissing, outputMissing),
                structured(text, role, taskWeak, contextMissing, constraintMissing, outputMissing),
                minimal(text, role, outputMissing, constraintMissing),
                questioning(text),
                placeholders);
    }

    /**
     * 整句版：直接给出一段完整、可复制的优化指令，不出现「待补充」占位符。
     *
     * <p>缺失的信息用通用要求补齐：角色缺失时用「经验丰富的行业专家」，
     * 背景不足时要求 AI 先确认，约束/格式缺失时给最小可用的默认要求。
     * 真正需要用户补充的项仍会通过 {@code placeholders} 单独提示。</p>
     */
    private String complete(String text, String role, boolean taskWeak,
                            boolean contextMissing, boolean constraintMissing, boolean outputMissing) {
        StringBuilder sb = new StringBuilder();
        if (role == null) {
            sb.append("你是一名经验丰富的行业专家。");
        }
        sb.append(oneLine(text).replaceAll("(?<=[。！？；：]) +", ""));
        appendSentenceEnd(sb);

        if (taskWeak) {
            appendClause(sb, "请交付一份可以直接使用的成品。");
        }
        if (contextMissing) {
            appendClause(sb, "如需更多背景，请先向我确认，不要自行编造。");
        }
        if (constraintMissing) {
            appendClause(sb, "请保持简洁、具体，避免空话。");
        }
        if (outputMissing) {
            appendClause(sb, "请用分点或表格展开。");
        }

        appendClause(sb, "请先给结论，再给必要细节；不确定的信息标注「待确认」，不要编造数据或引用。");
        return sb.toString();
    }

    private static void appendClause(StringBuilder sb, String clause) {
        if (sb.isEmpty()) {
            sb.append(clause);
            return;
        }
        char last = sb.charAt(sb.length() - 1);
        if ("。！？；：".indexOf(last) < 0 && last != '\n') {
            sb.append(' ');
        }
        sb.append(clause);
    }

    private static void appendSentenceEnd(StringBuilder sb) {
        if (sb.isEmpty()) {
            return;
        }
        char last = sb.charAt(sb.length() - 1);
        if ("。！？.!?".indexOf(last) < 0) {
            sb.append('。');
        }
    }

    private String structured(String text, String role, boolean taskWeak,
                              boolean contextMissing, boolean constraintMissing, boolean outputMissing) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 角色\n");
        sb.append(role != null
                ? "你是" + role + "。\n"
                : TODO_OPEN + "AI 的专业身份，例如「资深 Java 后端工程师」』\n");

        sb.append("\n# 任务\n").append(oneLine(text)).append('\n');
        if (taskWeak) {
            sb.append('\n').append(TODO_OPEN).append("最终交付物写具体些，例如「一份 800 字以内的周报初稿」』\n");
        }

        sb.append("\n# 背景\n");
        sb.append(contextMissing
                ? TODO_OPEN + "谁会用这份结果、为什么做、现在进行到哪一步』\n"
                : "见上文任务描述。\n");

        sb.append("\n# 约束\n");
        sb.append(constraintMissing
                ? TODO_OPEN + "字数、语气、绝对不能出现的内容』\n"
                : "见上文任务描述。\n");

        sb.append("\n# 输出格式\n");
        sb.append(outputMissing
                ? TODO_OPEN + "段落 / 分点清单 / Markdown 表格，以及大概多少字』\n"
                : "按上文要求组织，先给结论再展开。\n");

        sb.append("\n# 质量要求\n");
        sb.append("- 不确定的信息标注「待确认」，不要编造数据或引用\n");
        sb.append("- 先给结构和结论，再展开细节\n");
        sb.append("- 关键判断附一句话理由\n");
        return sb.toString();
    }

    private String minimal(String text, String role, boolean outputMissing, boolean constraintMissing) {
        String rolePart = role != null ? role : TODO_OPEN + "身份』";
        String taskPart = shorten(oneLine(text), 60);
        String outputPart = outputMissing ? TODO_OPEN + "形态：段落/清单/表格』" : "见上文要求";
        String constraintPart = constraintMissing ? TODO_OPEN + "字数与语气』" : "保持简洁";
        return "作为" + rolePart + "，帮我" + taskPart + "。输出：" + outputPart + "。限制：" + constraintPart + "。";
    }

    private String questioning(String text) {
        return """
                先不要直接给答案。

                请先问我 3 个能显著提升结果质量的问题，优先问清：
                1. 这份结果给谁看、要解决什么具体问题
                2. 需要什么形态的产出（段落 / 清单 / 表格 / 代码）
                3. 有哪些硬约束（字数、时间、风格、不能出现的内容）

                等我回答后，先给我一个提纲，我确认后再展开写。

                我的需求：""" + oneLine(text);
    }

    /**
     * 场景模板骨架版：不替用户打分，也不改动诊断结果，
     * 只把模板自带的五要素骨架作为“可填写的参考答案”附在报告里。
     */
    public String templatePrompt(Template template) {
        if (template == null) {
            return "";
        }
        return """
                # 场景：%s

                下面是这个场景的完整骨架。把「…」换成你的真实信息；
                不确定的写「待确认」，不要删掉你看不懂的部分。

                %s
                """.formatted(template.name(), template.skeleton().trim());
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s*\\n\\s*", " ").trim();
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static int scoreOf(Diagnosis diagnosis, String key) {
        return diagnosis.dimensions().stream()
                .filter(dimension -> dimension.key().equals(key))
                .mapToInt(DimensionScore::score)
                .findFirst()
                .orElse(0);
    }

    /**
     * 四个版本的改写结果。
     *
     * @param complete     整句版：一段连续文字，直接复制给 AI
     * @param structured   结构化版：按角色/任务/背景/约束/输出分段
     * @param minimal      极简版：一行说完
     * @param questioning  反问版：先问清关键信息再动手
     * @param placeholders 仍未完成的维度名，前端用来提示「还有 3 处要你填」
     */
    public record BuiltPrompts(
            String complete,
            String structured,
            String minimal,
            String questioning,
            List<String> placeholders) {
    }
}