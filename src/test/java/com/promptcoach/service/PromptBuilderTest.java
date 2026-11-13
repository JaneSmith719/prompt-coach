package com.promptcoach.service;

import com.promptcoach.model.Diagnosis;
import com.promptcoach.model.Template;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 改写器测试：缺口必须变成看得见的占位符，而不是被悄悄编满。
 */
class PromptBuilderTest {

    private final PromptAnalyzer analyzer = new PromptAnalyzer();
    private final PromptBuilder builder = new PromptBuilder();

    @Test
    @DisplayName("模糊需求会被写出占位符，并列出待补充项")
    void weakPromptLeavesPlaceholders() {
        String text = "帮我弄个周报";
        Diagnosis diagnosis = analyzer.analyze(text);

        PromptBuilder.BuiltPrompts built = builder.build(text, diagnosis);

        assertThat(built.structured()).contains(PromptBuilder.TODO_OPEN);
        assertThat(built.complete())
                .doesNotContain(PromptBuilder.TODO_OPEN)
                .contains("经验丰富的行业专家")
                .contains("请交付一份可以直接使用的成品")
                .contains("如需更多背景");
        assertThat(built.placeholders()).isNotEmpty();
        assertThat(built.placeholders()).contains("角色身份");
    }

    @Test
    @DisplayName("五要素齐全时不再留占位符，并沿用原有角色")
    void strongPromptKeepsRole() {
        String text = """
                你是资深 Java 后端工程师，请帮我分析这段订单查询代码的性能瓶颈。
                背景：我是大三实习生，在做电商订单系统，目前接口 QPS 300，目标 1000，压测工具用 JMeter。
                约束：不超过 500 字，语气正式，不要输出完整重写的代码。
                输出：用 Markdown 表格列出问题、原因和改法。""";
        Diagnosis diagnosis = analyzer.analyze(text);

        PromptBuilder.BuiltPrompts built = builder.build(text, diagnosis);

        assertThat(built.placeholders()).isEmpty();
        assertThat(built.structured()).contains("你是资深 Java 后端工程师。");
        assertThat(built.structured()).doesNotContain(PromptBuilder.TODO_OPEN);
        assertThat(built.complete())
                .doesNotContain(PromptBuilder.TODO_OPEN)
                .doesNotContain("\n")
                .doesNotContain("# 角色")
                .contains("你是资深 Java 后端工程师")
                .contains("请先给结论");
    }

    @Test
    @DisplayName("四种版本都包含原意，且结构完整")
    void variantsAreComplete() {
        String text = "帮我写个周报";
        PromptBuilder.BuiltPrompts built = builder.build(text, analyzer.analyze(text));

        assertThat(built.complete()).contains(text).doesNotContain("# ");
        assertThat(built.structured()).contains("# 角色", "# 任务", "# 背景", "# 约束", "# 输出格式");
        assertThat(built.minimal()).contains("作为").contains("输出：");
        assertThat(built.questioning()).contains("先不要直接给答案").contains(text);
    }

    @Test
    @DisplayName("多行输入会被压成一行，避免占位符之间结构错乱")
    void multilineInputIsFlattened() {
        String text = "帮我写周报\n第一行\n第二行";
        PromptBuilder.BuiltPrompts built = builder.build(text, analyzer.analyze(text));

        assertThat(built.minimal()).doesNotContain("\n");
    }

    @Test
    @DisplayName("模板骨架版保留场景名和完整骨架，不参与评分")
    void templatePromptKeepsSkeleton() {
        Template template = new TemplateLibrary().find("weekly-report").orElseThrow();

        String prompt = builder.templatePrompt(template);

        assertThat(prompt).contains("场景：周报总结");
        assertThat(prompt).contains("【本周记录】");
        assertThat(prompt).contains("下周计划");
        assertThat(prompt).contains("把「…」换成你的真实信息");
    }
}