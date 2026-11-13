package com.promptcoach.service;

import com.promptcoach.model.Diagnosis;
import com.promptcoach.model.DimensionScore;
import com.promptcoach.model.Template;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 诊断引擎的规则测试：保证同样的输入永远得到同样的分数，
 * 也保证「模糊需求低分、完整需求高分」这个核心判断成立。
 */
class PromptAnalyzerTest {

    private final PromptAnalyzer analyzer = new PromptAnalyzer();

    private static final String WEAK = "帮我写个周报";

    private static final String STRONG = """
            你是资深 Java 后端工程师，请帮我分析这段订单查询代码的性能瓶颈。
            背景：我是大三实习生，在做电商订单系统，目前接口 QPS 300，目标 1000，压测工具用 JMeter。
            约束：不超过 500 字，语气正式，不要输出完整重写的代码。
            输出：用 Markdown 表格列出问题、原因和改法。""";

    @Test
    @DisplayName("模糊的一句话应该拿低分，并指出角色、背景等缺口")
    void weakPromptScoresLow() {
        Diagnosis diagnosis = analyzer.analyze(WEAK);

        assertThat(diagnosis.score()).isLessThan(40);
        assertThat(diagnosis.grade()).isIn("C", "D");
        assertThat(diagnosis.gaps()).anyMatch(gap -> gap.contains("角色"));
        assertThat(diagnosis.gaps()).anyMatch(gap -> gap.contains("背景"));
        assertThat(diagnosis.questions()).isNotEmpty();
        assertThat(diagnosis.summary()).isNotBlank();
    }

    @Test
    @DisplayName("五要素齐全的需求应该拿到 S 级")
    void strongPromptScoresHigh() {
        Diagnosis diagnosis = analyzer.analyze(STRONG);

        assertThat(diagnosis.score()).isGreaterThanOrEqualTo(85);
        assertThat(diagnosis.grade()).isEqualTo("S");
        assertThat(diagnosis.gaps()).isEmpty();
    }

    @Test
    @DisplayName("总分永远不超过 100，且五个维度都在 0 到满分之间")
    void scoreNeverExceedsMax() {
        Diagnosis diagnosis = analyzer.analyze(STRONG + "\n" + STRONG + "\n" + STRONG);

        assertThat(diagnosis.score()).isLessThanOrEqualTo(PromptAnalyzer.MAX_SCORE);
        assertThat(diagnosis.dimensions()).hasSize(5);
        for (DimensionScore dimension : diagnosis.dimensions()) {
            assertThat(dimension.score()).isBetween(0, dimension.max());
        }
    }

    @Test
    @DisplayName("识别「作为产品经理」这类身份写法")
    void detectsRolePhrase() {
        Diagnosis diagnosis = analyzer.analyze("作为产品经理，帮我写需求文档。");

        DimensionScore role = dimension(diagnosis, "role");
        assertThat(role.score()).isEqualTo(15);
        assertThat(role.evidence()).contains("产品经理");
    }

    @Test
    @DisplayName("「弄一下那个东西」这类模糊词会扣掉任务分")
    void vagueWordsReduceTaskScore() {
        Diagnosis vague = analyzer.analyze("帮我弄一下那个东西");
        Diagnosis clear = analyzer.analyze("帮我写一份 800 字的产品需求文档");

        assertThat(dimension(vague, "task").score())
                .isLessThan(dimension(clear, "task").score());
    }

    @Test
    @DisplayName("空输入不应该抛异常，而是给出 0 分")
    void blankInputIsSafe() {
        Diagnosis diagnosis = analyzer.analyze("   ");

        assertThat(diagnosis.score()).isZero();
        assertThat(diagnosis.dimensions()).hasSize(5);
    }

    @Test
    @DisplayName("追问不超过 5 个，避免一次抛给用户太多问题")
    void questionsAreCapped() {
        assertThat(analyzer.analyze("写个东西").questions()).hasSizeLessThanOrEqualTo(5);
        assertThat(analyzer.analyze(WEAK).questions()).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("模板库里的 90+ 样例必须真的达到 90 分")
    void highScoreSamplesReachNinety() {
        TemplateLibrary library = new TemplateLibrary();

        for (String id : List.of("weekly-report", "code-review", "paper-polish")) {
            Template template = library.find(id).orElseThrow();
            Diagnosis diagnosis = analyzer.analyze(template.example());
            assertThat(diagnosis.score())
                    .as("模板 %s 的示例分数", id)
                    .isGreaterThanOrEqualTo(90);
        }
    }

    private DimensionScore dimension(Diagnosis diagnosis, String key) {
        return diagnosis.dimensions().stream()
                .filter(dimension -> dimension.key().equals(key))
                .findFirst()
                .orElseThrow();
    }
}