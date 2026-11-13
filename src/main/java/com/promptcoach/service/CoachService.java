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
 * <p>降级策略是这个类最重要的行为：大模型没配、超时、报错、配额用尽，
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

    private static final int MAX_TEMPLATE_ID_LENGTH = 64;

    private final PromptAnalyzer analyzer;
    private final PromptBuilder promptBuilder;
    private final AdviceEngine adviceEngine;
    private final TemplateLibrary templateLibrary;
    private final HistoryStore historyStore;
    private final LlmClient llmClient;
    private final EnhanceGuard enhanceGuard;
    private final CoachProperties properties;

    public CoachService(PromptAnalyzer analyzer,
                        PromptBuilder promptBuilder,
                        AdviceEngine adviceEngine,
                        TemplateLibrary templateLibrary,
                        HistoryStore historyStore,
                        LlmClient llmClient,
                        EnhanceGuard enhanceGuard,
                        CoachProperties properties) {
        this.analyzer = analyzer;
        this.promptBuilder = promptBuilder;
        this.adviceEngine = adviceEngine;
        this.templateLibrary = templateLibrary;
        this.historyStore = historyStore;
        this.llmClient = llmClient;
        this.enhanceGuard = enhanceGuard;
        this.properties = properties;
    }

    public CoachReport coach(CoachRequest request, String sessionId) {
        long startedAt = System.nanoTime();
        String text = validate(request == null ? null : request.text());

        String notice = null;
        String templateId = normalizeTemplateId(request == null ? null : request.templateId());
        if (templateId != null && templateId.length() > MAX_TEMPLATE_ID_LENGTH) {
            notice = append(notice, "模板标识过长，已按通用规则诊断。");
            templateId = null;
        }
        Template template = templateId == null ? null : templateLibrary.find(templateId).orElse(null);
        if (templateId != null && template == null) {
            notice = append(notice, "模板「" + templateId + "」不存在，已按通用规则诊断。");
        }

        Diagnosis diagnosis = analyzer.analyze(text);
        PromptBuilder.BuiltPrompts built = promptBuilder.build(text, diagnosis);
        List<Advice> advice = adviceEngine.advise(text, diagnosis);

        boolean wantEnhance = request != null && Boolean.TRUE.equals(request.enhance());
        boolean enhanced = false;
        String mainPrompt = built.structured();

        if (wantEnhance) {
            if (!llmClient.isAvailable()) {
                notice = append(notice, "没有配置 AI_API_KEY，本次使用本地规则引擎生成。");
            } else {
                EnhanceGuard.Decision decision = enhanceGuard.tryAcquire(sessionId);
                if (!decision.allowed()) {
                    notice = append(notice, quotaNotice(decision.reason()));
                } else {
                    try {
                        mainPrompt = llmClient.complete(
                                ENHANCE_SYSTEM, enhancePrompt(text, diagnosis, template));
                        enhanced = true;
                    } catch (LlmException e) {
                        log.warn("大模型增强失败，降级为本地规则：{}", e.getMessage());
                        notice = append(notice, "大模型增强失败，已自动降级为本地规则引擎：" + e.getMessage());
                    } finally {
                        if (decision.leased()) {
                            enhanceGuard.release();
                        }
                    }
                }
            }
        }

        List<PromptVariant> variants = variants(mainPrompt, built, enhanced, template);
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
                template == null ? null : template.id(),
                template == null ? null : template.name(),
                template == null ? List.of() : template.tips(),
                enhanced,
                enhanced ? "llm" : "local",
                llmClient.isAvailable() ? llmClient.model() : null,
                notice,
                elapsedMs,
                createdAt);

        historyStore.add(sessionId, new HistoryEntry(id, text, diagnosis.score(), diagnosis.grade(),
                enhanced, createdAt));
        return report;
    }

    public List<Template> templates() {
        return templateLibrary.all();
    }

    public List<HistoryEntry> history(String sessionId, int limit) {
        int capped = Math.min(Math.max(limit, 1), properties.getHistoryLimit());
        return historyStore.recent(sessionId, capped);
    }

    public void clearHistory(String sessionId) {
        historyStore.clear(sessionId);
    }

    public Map<String, Object> meta() {
        return Map.of(
                "name", "PromptCoach",
                "version", version(),
                "llmAvailable", llmClient.isAvailable(),
                "model", llmClient.isAvailable() ? llmClient.model() : "",
                "maxInputChars", properties.getMaxInputChars(),
                "templateCount", templateLibrary.all().size(),
                "enhancePerHour", enhanceGuard.perHour(),
                "enhanceEnabled", enhanceGuard.isEnabled());
    }

    private List<PromptVariant> variants(String mainPrompt,
                                        PromptBuilder.BuiltPrompts built,
                                        boolean enhanced,
                                        Template template) {
        List<PromptVariant> variants = new ArrayList<>();
        variants.add(new PromptVariant(
                enhanced ? "LLM 增强版" : "结构化版",
                enhanced ? "由大模型重写，通常更完整" : "五要素齐全，可直接粘贴使用",
                mainPrompt));
        if (enhanced) {
            variants.add(new PromptVariant("本地结构化版", "规则引擎拼装，完全可预测", built.structured()));
        }
        if (template != null) {
            variants.add(new PromptVariant(
                    "模板骨架版",
                    "按「" + template.name() + "」骨架填槽位，适合从零开始",
                    promptBuilder.templatePrompt(template)));
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

    private String enhancePrompt(String text, Diagnosis diagnosis, Template template) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户原始需求】\n").append(text).append("\n\n");
        sb.append("【本地诊断】得分 ").append(diagnosis.score()).append("/100。");
        if (!diagnosis.gaps().isEmpty()) {
            sb.append("缺口：").append(String.join("；", diagnosis.gaps())).append("。");
        }
        if (!diagnosis.questions().isEmpty()) {
            sb.append("\n用户可能还没想清楚的点：").append(String.join("；", diagnosis.questions()));
        }
        if (template != null) {
            sb.append("\n\n【场景模板】").append(template.name()).append("\n")
                    .append(template.skeleton().trim());
            if (!template.tips().isEmpty()) {
                sb.append("\n场景提示：").append(String.join("；", template.tips()));
            }
        }
        sb.append("\n\n【改写要求】补齐上述缺口；用户没给的信息保留『待补充：…』占位，不要脑补。");
        return sb.toString();
    }

    private String quotaNotice(EnhanceGuard.Reason reason) {
        if (reason == EnhanceGuard.Reason.SESSION_LIMIT) {
            return "本次增强次数已达上限（每小时 " + enhanceGuard.perHour()
                    + " 次），已使用本地规则引擎。";
        }
        return "当前增强请求较多，已使用本地规则引擎，请稍后再试。";
    }

    private String version() {
        Package pkg = CoachService.class.getPackage();
        String implementationVersion = pkg == null ? null : pkg.getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
                ? properties.getVersion()
                : implementationVersion;
    }

    private static String normalizeTemplateId(String raw) {
        if (raw == null) {
            return null;
        }
        String id = raw.trim();
        return id.isEmpty() ? null : id;
    }

    private static String append(String current, String next) {
        if (current == null || current.isBlank()) {
            return next;
        }
        if (next == null || next.isBlank()) {
            return current;
        }
        return current + "；" + next;
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