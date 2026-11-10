package com.promptcoach.service;

import com.promptcoach.config.CoachProperties;
import com.promptcoach.llm.LlmClient;
import com.promptcoach.llm.LlmException;
import com.promptcoach.model.Advice;
import com.promptcoach.model.CoachReport;
import com.promptcoach.model.CoachRequest;
import com.promptcoach.model.Diagnosis;
import com.promptcoach.model.HistoryEntry;
import com.promptcoach.model.PromptVariant;
import com.promptcoach.model.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 编排层：诊断 → 改写 → 建议 → 可选的大模型增强 → 记历史。
 *
 * <p>降级策略是这个类最重要的行为：大模型没配、超时、报错，
 * 都必须给出可用的结果，而不是让用户看到 500。</p>
 */
@Service
public class CoachService {

    private static final Logger log = LoggerFactory.getLogger(CoachService.class);

    private static final String ENHANCE_SYSTEM = """
            你是一位中文提示词工程师，擅长把模糊需求改写成可以直接使用的高质量提示词。
            规则：
            1. 必须包含五部分：角色、任务、背景、约束、输出格式，用 Markdown 二级小标题。
            2. 严格保留用户原意，不要编造用户没提供的事实；需要假设时写成「假设：…」。
            3. 用户没提供的信息用『待补充：…』占位，不要替用户脑补。
            4. 输出格式要具体（段落 / 清单 / 表格，以及大致字数）。
            5. 只输出提示词本身，不要解释，不要加任何前后缀。
            """;

    private final PromptAnalyzer analyzer;
    private final PromptBuilder promptBuilder;
    private final AdviceEngine adviceEngine;
    private final TemplateLibrary templateLibrary;
    private final HistoryStore historyStore;
    private final LlmClient llmClient;
    private final CoachProperties properties;

    public CoachService(PromptAnalyzer analyzer,
                        PromptBuilder promptBuilder,
                        AdviceEngine adviceEngine,
                        TemplateLibrary templateLibrary,
                        HistoryStore historyStore,
                        LlmClient llmClient,
                        CoachProperties properties) {
        this.analyzer = analyzer;
        this.promptBuilder = promptBuilder;
        this.adviceEngine = adviceEngine;
        this.templateLibrary = templateLibrary;
        this.historyStore = historyStore;
        this.llmClient = llmClient;
        this.properties = properties;
    }

    public CoachReport coach(CoachRequest request) {
        long startedAt = System.nanoTime();
        String text = validate(request == null ? null : request.text());

        Diagnosis diagnosis = analyzer.analyze(text);
        PromptBuilder.BuiltPrompts built = promptBuilder.build(text, diagnosis);
        List<Advice> advice = adviceEngine.advise(text, diagnosis);

        boolean wantEnhance = request != null && Boolean.TRUE.equals(request.enhance());
        boolean enhanced = false;
        String notice = null;
        String mainPrompt = built.structured();

        if (wantEnhance) {
            if (!llmClient.isAvailable()) {
                notice = "没有配置 AI_API_KEY，本次使用本地规则引擎生成。";
            } else {
                try {
                    mainPrompt = llmClient.complete(ENHANCE_SYSTEM, enhancePrompt(text, diagnosis, built));
                    enhanced = true;
                } catch (LlmException e) {
                    log.warn("大模型增强失败，降级为本地规则：{}", e.getMessage());
                    notice = "大模型增强失败，已自动降级为本地规则引擎：" + e.getMessage();
                }
            }
        }

        List<PromptVariant> variants = variants(mainPrompt, built, enhanced);
        String id = UUID.randomUUID().toString().substring(0, 8);
        Instant createdAt = Instant.now();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        CoachReport report = new CoachReport(
                id,
                text,
                diagnosis,
                Math.max(0, 5 - built.placeholders().size()),
                built.placeholders(),
                mainPrompt,
                variants,
                advice,
                nextSteps(built),
                enhanced,
                enhanced ? "llm" : "local",
                llmClient.isAvailable() ? llmClient.model() : null,
                notice,
                elapsedMs,
                createdAt);

        historyStore.add(new HistoryEntry(id, text, diagnosis.score(), diagnosis.grade(),
                enhanced, createdAt));
        return report;
    }

    public List<Template> templates() {
        return templateLibrary.all();
    }

    public List<HistoryEntry> history(int limit) {
        return historyStore.recent(limit);
    }

    public void clearHistory() {
        historyStore.clear();
    }

    public Map<String, Object> meta() {
        return Map.of(
                "name", "PromptCoach",
                "version", "1.0.0",
                "llmAvailable", llmClient.isAvailable(),
                "model", llmClient.isAvailable() ? llmClient.model() : "",
                "maxInputChars", properties.getMaxInputChars(),
                "templateCount", templateLibrary.all().size());
    }

    private List<PromptVariant> variants(String mainPrompt,
                                        PromptBuilder.BuiltPrompts built,
                                        boolean enhanced) {
        List<PromptVariant> variants = new ArrayList<>();
        variants.add(new PromptVariant(
                enhanced ? "LLM 增强版" : "结构化版",
                enhanced ? "由大模型重写，通常更完整" : "五要素齐全，可直接粘贴使用",
                mainPrompt));
        if (enhanced) {
            variants.add(new PromptVariant("本地结构化版", "规则引擎拼装，完全可预测", built.structured()));
        }
        variants.add(new PromptVariant("极简版", "一行说完，适合日常轻量任务", built.minimal()));
        variants.add(new PromptVariant("反问版", "信息不足时先让 AI 反问，避免它硬编", built.questioning()));
        return variants;
    }

    private List<String> nextSteps(PromptBuilder.BuiltPrompts built) {
        List<String> steps = new ArrayList<>();
        if (!built.placeholders().isEmpty()) {
            steps.add("填掉 " + built.placeholders().size() + " 处『待补充』，再点一次诊断看分数变化");
        }
        steps.add("把改写稿发给 AI；结果不满意时，把它的输出贴回来再诊断一次");
        steps.add("从模板库选一个最接近的场景，用骨架改比从零写快");
        return steps;
    }

    private String enhancePrompt(String text, Diagnosis diagnosis, PromptBuilder.BuiltPrompts built) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户原始需求】\n").append(text).append("\n\n");
        sb.append("【本地诊断】得分 ").append(diagnosis.score()).append("/100。");
        if (!diagnosis.gaps().isEmpty()) {
            sb.append("缺口：").append(String.join("；", diagnosis.gaps())).append("。");
        }
        if (!diagnosis.questions().isEmpty()) {
            sb.append("\n用户可能还没想清楚的点：").append(String.join("；", diagnosis.questions()));
        }
        sb.append("\n\n【改写要求】补齐上述缺口；用户没给的信息保留『待补充：…』占位，不要脑补。");
        return sb.toString();
    }

    private String validate(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("请输入你的需求，比如：帮我写个周报");
        }
        int max = properties.getMaxInputChars();
        if (text.length() > max) {
            throw new IllegalArgumentException("输入太长（" + text.length() + " 字），请控制在 " + max + " 字以内");
        }
        return text;
    }
}