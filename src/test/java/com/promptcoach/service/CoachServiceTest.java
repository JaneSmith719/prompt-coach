package com.promptcoach.service;

import com.promptcoach.config.CoachProperties;
import com.promptcoach.llm.LlmClient;
import com.promptcoach.llm.LlmException;
import com.promptcoach.model.CoachReport;
import com.promptcoach.model.CoachRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;

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
                .coach(new CoachRequest("帮我写个周报", false, null), "session-a");

        assertThat(report.mode()).isEqualTo("local");
        assertThat(report.llmEnhanced()).isFalse();
        assertThat(report.diagnosis().score()).isPositive();
        assertThat(report.structuredPrompt()).isNotBlank();
        assertThat(report.variants()).hasSize(3);
        assertThat(report.advice()).isNotEmpty();
        assertThat(report.nextSteps()).isNotEmpty();
        assertThat(report.coverage()).isBetween(0, 5);
        assertThat(report.templateName()).isNull();
        assertThat(report.templateTips()).isEmpty();
    }

    @Test
    @DisplayName("要求增强但没配置 Key：给出提示并继续用本地规则")
    void enhanceWithoutKeyFallsBack() {
        CoachReport report = service(new StubLlm(false, "", false))
                .coach(new CoachRequest("帮我写个周报", true, null), "session-a");

        assertThat(report.mode()).isEqualTo("local");
        assertThat(report.notice()).contains("没有配置");
    }

    @Test
    @DisplayName("配置了 Key：主提示词换成大模型产出，并保留本地版本对照")
    void enhanceWithKeyUsesModelOutput() {
        CoachReport report = service(new StubLlm(true, "# 角色\n你是资深产品经理。", false))
                .coach(new CoachRequest("帮我写个周报", true, null), "session-a");

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
                .coach(new CoachRequest("帮我写个周报", true, null), "session-a");

        assertThat(report.mode()).isEqualTo("local");
        assertThat(report.notice()).contains("降级");
        assertThat(report.structuredPrompt()).isNotBlank();
    }

    @Test
    @DisplayName("增强次数超过配额：不调用模型，降级为本地规则")
    void quotaExceededFallsBackWithoutCallingModel() {
        properties.getEnhance().setPerSessionPerHour(1);
        properties.getEnhance().setGlobalPerHour(0);
        properties.getEnhance().setMaxConcurrent(1);
        StubLlm llm = new StubLlm(true, "# 角色\n你是资深产品经理。", false);
        CoachService service = service(llm);

        CoachReport first = service.coach(new CoachRequest("帮我写个周报", true, null), "session-a");
        CoachReport second = service.coach(new CoachRequest("帮我写个周报", true, null), "session-a");

        assertThat(first.mode()).isEqualTo("llm");
        assertThat(second.mode()).isEqualTo("local");
        assertThat(second.notice()).contains("上限");
        assertThat(llm.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("模板生效：报告带模板名和提示，并多一个骨架版")
    void templateAddsSkeletonVariantAndTips() {
        CoachReport report = service(new StubLlm(false, "", false))
                .coach(new CoachRequest("帮我写个周报", false, "weekly-report"), "session-a");

        assertThat(report.templateId()).isEqualTo("weekly-report");
        assertThat(report.templateName()).isEqualTo("周报总结");
        assertThat(report.templateTips()).hasSize(3);
        assertThat(report.variants()).hasSize(4);
        assertThat(report.variants()).anyMatch(variant -> variant.label().equals("模板骨架版"));
        assertThat(report.variants().get(1).prompt())
                .contains("周报总结")
                .contains("【本周记录】");
    }

    @Test
    @DisplayName("未知模板不报错：按通用规则诊断并提示")
    void unknownTemplateFallsBackWithNotice() {
        CoachReport report = service(new StubLlm(false, "", false))
                .coach(new CoachRequest("帮我写个周报", false, "not-exist"), "session-a");

        assertThat(report.templateName()).isNull();
        assertThat(report.variants()).hasSize(3);
        assertThat(report.notice()).contains("不存在");
    }

    @Test
    @DisplayName("空输入和超长输入都会被拒绝")
    void rejectsInvalidInput() {
        CoachService service = service(new StubLlm(false, "", false));

        assertThatThrownBy(() -> service.coach(new CoachRequest("   ", false, null), "session-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请输入");

        properties.setMaxInputChars(5);
        assertThatThrownBy(() -> service.coach(new CoachRequest("帮我写一个很长的周报", false, null), "session-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("太长");
    }

    @Test
    @DisplayName("历史记录按会话隔离、按条数保留，并且只清空当前会话")
    void historyIsIsolatedBoundedAndClearable() {
        CoachService service = service(new StubLlm(false, "", false));
        for (int i = 0; i < 3; i++) {
            service.coach(new CoachRequest("帮我写第 " + i + " 份周报", false, null), "session-a");
        }
        service.coach(new CoachRequest("另一个人的周报", false, null), "session-b");

        assertThat(service.history("session-a", 2)).hasSize(2);
        assertThat(service.history("session-a", 10).get(0).text()).contains("第 2 份");
        assertThat(service.history("session-b", 10)).hasSize(1);
        assertThat(service.history("session-b", 10).get(0).text()).contains("另一个人");

        service.clearHistory("session-a");
        assertThat(service.history("session-a", 10)).isEmpty();
        assertThat(service.history("session-b", 10)).hasSize(1);
    }

    @Test
    @DisplayName("模板库和运行信息开箱可用，版本号不再写死")
    void templatesAndMetaAreAvailable() {
        CoachService service = service(new StubLlm(false, "", false));

        assertThat(service.templates()).isNotEmpty();
        assertThat(service.meta()).containsEntry("llmAvailable", false);
        assertThat(service.meta()).containsEntry("version", "1.1.0");
        assertThat(service.meta()).containsEntry("enhancePerHour", 10);
    }

    private CoachService service(LlmClient llmClient) {
        return new CoachService(
                new PromptAnalyzer(),
                new PromptBuilder(),
                new AdviceEngine(),
                new TemplateLibrary(),
                historyStore,
                llmClient,
                new EnhanceGuard(properties, Clock.systemUTC()),
                properties);
    }

    /** 测试替身：不联网，也能把三条分支（可用 / 不可用 / 报错）走一遍 */
    private static final class StubLlm implements LlmClient {

        private final boolean available;
        private final String answer;
        private final boolean fail;
        private int calls;

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
            calls++;
            if (fail) {
                throw new LlmException("模拟接口超时");
            }
            return answer;
        }
    }
}