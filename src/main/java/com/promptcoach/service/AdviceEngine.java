package com.promptcoach.service;

import com.promptcoach.model.Advice;
import com.promptcoach.model.Diagnosis;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提效建议引擎。
 *
 * <p>先判断你这件事属于哪一类（写东西 / 写代码 / 做分析 / 学习 / 设计 / 做选择），
 * 再给这一类最管用的两条技巧，最后补上通用建议。规则化实现，
 * 保证同样输入给同样建议，方便写单元测试。</p>
 */
@Component
public class AdviceEngine {

    private static final Map<TaskType, String[]> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put(TaskType.CODE, new String[]{
                "代码", "报错", "bug", "异常", "堆栈", "接口", "重构", "单元测试", "sql",
                "数据库", "前端", "后端", "java", "spring", "python", "算法", "部署", "性能"});
        KEYWORDS.put(TaskType.DECISION, new String[]{
                "选型", "要不要", "值不值", "权衡", "哪个好", "选一个", "该不该", "推荐哪个"});
        KEYWORDS.put(TaskType.WRITING, new String[]{
                "文案", "周报", "邮件", "论文", "润色", "改写", "述职", "简历", "自我介绍",
                "报告", "文章", "发言稿", "推文", "朋友圈"});
        KEYWORDS.put(TaskType.ANALYSIS, new String[]{
                "分析", "调研", "竞品", "对比", "评估", "原因", "复盘", "数据", "趋势"});
        KEYWORDS.put(TaskType.LEARNING, new String[]{
                "学", "复习", "计划", "面试", "考试", "入门", "教程", "知识点", "讲清楚", "解释"});
        KEYWORDS.put(TaskType.DESIGN, new String[]{
                "设计", "架构", "原型", "ppt", "大纲", "方案", "流程", "画"});
    }

    public List<Advice> advise(String text, Diagnosis diagnosis) {
        TaskType type = classify(text);
        List<Advice> result = new ArrayList<>(typeAdvice(type));
        result.addAll(foundationAdvice(text, diagnosis));

        Map<String, Advice> deduped = new LinkedHashMap<>();
        for (Advice advice : result) {
            deduped.putIfAbsent(advice.title(), advice);
        }
        return deduped.values().stream().limit(5).toList();
    }

    public TaskType classify(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        TaskType best = TaskType.GENERAL;
        int bestHits = 0;
        for (Map.Entry<TaskType, String[]> entry : KEYWORDS.entrySet()) {
            int hits = 0;
            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword)) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = entry.getKey();
            }
        }
        return best;
    }

    private List<Advice> typeAdvice(TaskType type) {
        return switch (type) {
            case CODE -> List.of(
                    new Advice("把报错堆栈和关键代码整段贴进去",
                            "只说「跑不起来」时模型只能猜。一次给全：完整堆栈 + 出错那几行 + 你期望的结果。", "高"),
                    new Advice("要最小改动，并让它解释为什么",
                            "让它输出「改哪一行、为什么改」，而不是重写整个文件——你能看懂，也方便回滚。", "中"));
            case WRITING -> List.of(
                    new Advice("先给它一段你满意的样文",
                            "给两三句「就要这个味道」的示例，比说「专业一点」「活泼一点」有效得多。", "高"),
                    new Advice("两段式：先提纲，再正文",
                            "一次生成全文容易前松后紧。先要 5 条提纲，确认后再逐段扩写。", "中"));
            case ANALYSIS -> List.of(
                    new Advice("先把评判标准说清楚",
                            "列出维度（成本 / 速度 / 可维护性），让它按维度打表，比让它自由发挥更可靠。", "高"),
                    new Advice("要求区分事实与推测",
                            "让它标出哪些来自你给的资料、哪些是推测，能挡掉一批「看起来很有道理」的错误。", "中"));
            case LEARNING -> List.of(
                    new Advice("告诉它你的水平锚点",
                            "一句「我大三，学过 Java 基础但没碰过并发」，能让解释深度立刻贴合你。", "高"),
                    new Advice("学完让它出 3 道自测题",
                            "读完就忘是常态。让它根据你的答案指出哪块没真懂，比再读一遍有效。", "中"));
            case DESIGN -> List.of(
                    new Advice("先要三个方向，再展开",
                            "直接要方案会被第一个想法锁死。先要 3 个思路和各自代价，再挑一个深入。", "高"),
                    new Advice("要一个 30 分钟能验证的最小动作",
                            "让它给出今天就能试的第一步，而不是一份需要两周的规划。", "中"));
            case DECISION -> List.of(
                    new Advice("要选项和权衡，不要只要结论",
                            "让它按维度打分并说明扣分理由，你才有判断依据；只给结论等于把决定权交出去。", "高"),
                    new Advice("追问「什么情况下你会推翻自己」",
                            "让它给出反例条件，能暴露它其实没想清楚的地方。", "中"));
            default -> List.of(
                    new Advice("用五段式打底",
                            "角色 + 任务 + 背景 + 约束 + 格式，五段齐全后模型输出的稳定性会明显提升。", "高"),
                    new Advice("复杂任务拆成 2-3 轮",
                            "一次只问一件事，第二轮再追问细节，比一次塞满更准。", "中"));
        };
    }

    private List<Advice> foundationAdvice(String text, Diagnosis diagnosis) {
        List<Advice> advice = new ArrayList<>();
        if (diagnosis.score() < 40) {
            advice.add(new Advice("分数低不是模型不行，是需求没说清",
                    "先答完上面列出的追问，再跑一次诊断——这一步的提升通常比换模型更大。", "高"));
        }
        advice.add(new Advice("让它开工前先复述一遍",
                "让它用三句话复述它理解的任务，偏差会在成本最低的时候暴露出来。", "中"));
        advice.add(new Advice("要求标注不确定处",
                "对没把握的内容标「待确认」，能明显减少一本正经的编造。", "中"));
        if (text != null && text.length() < 30) {
            advice.add(new Advice("一句话只够说清一件事",
                    "把「背景」和「要什么」分开写，两句话就能把命中率拉上一个台阶。", "中"));
        }
        return advice;
    }

    /** 任务类型，用于给建议分组 */
    public enum TaskType {
        CODE, DECISION, WRITING, ANALYSIS, LEARNING, DESIGN, GENERAL
    }
}