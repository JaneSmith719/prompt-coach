package com.promptcoach.service;

import com.promptcoach.config.CoachProperties;
import com.promptcoach.llm.LlmClient;
import com.promptcoach.llm.LlmException;
import com.promptcoach.model.CoachReport;
import com.promptcoach.model.CoachRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编排层测试，重点覆盖「大模型不可用时必须优雅降级」这条主分支。
 */
class CoachServiceTest {

    private CoachProperties properties;
    private HistoryStore historyStore;

    @BeforeEach
    void setUp() {
        properties = new CoachProperties();
        historyStore = new HistoryStore(properties);
    }

    @Test
    @DisplayName("本地模式：不需要任何外部服务也能给出完整报告")
    void localModeProducesFullReport() {
        CoachReport report = service(new StubLlm(false, "", false))
                .coach(new CoachRequest("帮我写个周报", false, null));

        assertThat(report.mode()).isEqualTo("local");
        assertThat(report.llmEnhanced()).isFalse();
        assertThat(report.diagnosis().score()).isPositive();
        assertThat(report.structuredPrompt()).isNotBlank();
        assertThat(report.variants()).hasSize(3);
        assertThat(report.advice()).isNotEmpty();
        assertThat(report.nextSteps()).isNotEmpty();
        assertThat(report.coverage()).isBetween(0, 5);
    }

    @Test
    @DisplayName("要求增强但没配置 Key：给出提示并继续用本地规则")
    void enhanceWithoutKeyFallsBack() {
        CoachReport report = service(new StubLlm(false, "", false))
                .coach(new CoachRequest("帮我写个周报", true, null));

        assertThat(report.mode()).isEqualTo("local");
        assertThat(report.notice()).contains("没有配置");
    }

    @Test
    @DisplayName("配置了 Key：主提示词换成大模型产出，并保留本地版本对照")
    void enhanceWithKeyUsesModelOutput() {
        CoachReport report = service(new StubLlm(true, "# 角色\n你是资深产品经理。", false))
                .coach(new CoachRequest("帮我写个周报", true, null));

        assertThat(report.mode()).isEqualTo("llm");
        assertThat(report.llmEnhanced()).isTrue();
        assertThat(report.structuredPrompt()).contains("资深产品经理");
        assertThat(report.variants()).hasSize(4);
        assertThat(report.variants().get(0).label()).contains("增强");
        assertThat(report.model()).isEqualTo("stub-model");
    }

    @Test
    @DisplayName("大模型报错：自动降级，不把 500 抛给用户")
    void modelFailureDegradesGracefully() {
        CoachReport report = service(new StubLlm(true, "", true))
                .coach(new CoachRequest("帮我写个周报", true, null));

        assertThat(report.mode()).isEqualTo("local");
        assertThat(report.notice()).contains("降级");
        assertThat(report.structuredPrompt()).isNotBlank();
    }

    @Test
    @DisplayName("空输入和超长输入都会被拒绝")
    void rejectsInvalidInput() {
        CoachService service = service(new StubLlm(false, "", false));

        assertThatThrownBy(() -> service.coach(new CoachRequest("   ", false, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请输入");

        properties.setMaxInputChars(5);
        assertThatThrownBy(() -> service.coach(new CoachRequest("帮我写一个很长的周报", false, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("太长");
    }

    @Test
    @DisplayName("历史记录按条数保留，并且可以清空")
    void historyIsBoundedAndClearable() {
        CoachService service = service(new StubLlm(false, "", false));
        for (int i = 0; i < 3; i++) {
            service.coach(new CoachRequest("帮我写第 " + i + " 份周报", false, null));
        }

        assertThat(service.history(2)).hasSize(2);
        assertThat(service.history(10).get(0).text()).contains("第 2 份");

        service.clearHistory();
        assertThat(service.history(10)).isEmpty();
    }

    @Test
    @DisplayName("模板库开箱可用")
    void templatesAreAvailable() {
        CoachService service = service(new StubLlm(false, "", false));

        assertThat(service.templates()).isNotEmpty();
        assertThat(service.meta()).containsEntry("llmAvailable", false);
    }

    private CoachService service(LlmClient llmClient) {
        return new CoachService(
                new PromptAnalyzer(),
                new PromptBuilder(),
                new AdviceEngine(),
                new TemplateLibrary(),
                historyStore,
                llmClient,
                properties);
    }

    /** 测试替身：不联网，也能把三条分支（可用 / 不可用 / 报错）走一遍 */
    private static final class StubLlm implements LlmClient {

        private final boolean available;
        private final String answer;
        private final boolean fail;

        private StubLlm(boolean available, String answer, boolean fail) {
            this.available = available;
            this.answer = answer;
            this.fail = fail;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String model() {
            return "stub-model";
        }

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            if (fail) {
                throw new LlmException("模拟接口超时");
            }
            return answer;
        }
    }
}