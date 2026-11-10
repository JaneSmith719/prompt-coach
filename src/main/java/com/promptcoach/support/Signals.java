package com.promptcoach.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词里的「信号词」词表。
 *
 * <p>整个诊断引擎不依赖大模型：它靠这组正则识别用户有没有说清
 * 角色 / 任务 / 背景 / 约束 / 输出格式。规则全部集中在这里，
 * 想扩展词表只改这个文件即可，不影响其他逻辑。</p>
 */
public final class Signals {

    private Signals() {
    }

    /** 角色信号：出现了在指定身份的词 */
    public static final Pattern ROLE = Pattern.compile(
            "作为|你是|扮演|假设你是|身份|角色|专家|工程师|顾问|老师|导师|面试官|律师|医生|编辑|主编|分析师|设计师|产品经理"
                    + "|act as|you are|role",
            Pattern.CASE_INSENSITIVE);

    /** 从「作为 xxx」「你是 xxx」里把身份短语抠出来 */
    public static final Pattern ROLE_PHRASE = Pattern.compile(
            "(?:作为|你是|扮演|假设你是)(?:一名|一个|一位|个)?([^，。,.；;！!？?\\n]{2,20})");

    /** 「以产品经理的视角」这类写法 */
    public static final Pattern ROLE_ALT = Pattern.compile(
            "以([^，。,.；;！!？?\\n]{2,20}?)(?:的)?(?:视角|身份|角度|口吻)");

    /** 动作信号：有明确动词才算「任务清晰」 */
    public static final Pattern ACTION = Pattern.compile(
            "写|撰写|起草|生成|总结|归纳|改写|润色|校对|翻译|分析|拆解|对比|比较|评估|审查|评审|重构|解释|规划|设计|制定"
                    + "|列出|提炼|诊断|出题|优化|修复|点评|打分|推荐|梳理|复盘|找错|报错|review|debug|refactor",
            Pattern.CASE_INSENSITIVE);

    /** 背景信号 */
    public static final Pattern CONTEXT = Pattern.compile(
            "背景|目前|现在|现状|情况|我是|我们|我的|项目|课程|最近|因为|目标|场景|需求|业务|实习|作业|论文|面试|团队"
                    + "|公司|学校|deadline|context",
            Pattern.CASE_INSENSITIVE);

    /** 受众信号：知道写给谁看 */
    public static final Pattern AUDIENCE = Pattern.compile(
            "给(?:我|他|她|老师|老板|领导|同事|客户|面试官|读者|导师|学长|学姐|家长|团队|组里)"
                    + "|面向|读者|受众|用户|领导看|老板看|同学看|用于汇报|讲课",
            Pattern.CASE_INSENSITIVE);

    /** 约束信号：字数、风格、边界 */
    public static final Pattern CONSTRAINT = Pattern.compile(
            "字数|字以内|不超过|最多|最少|必须|不要|避免|别太|不要太|风格|语气|口吻|限制|范围|期限|deadline|简洁|正式"
                    + "|口语|幽默|严谨|控制在|限定|篇幅|通俗|专业一点|小学生也能懂",
            Pattern.CASE_INSENSITIVE);

    /** 输出格式信号：明确要什么形态的产出 */
    public static final Pattern OUTPUT = Pattern.compile(
            "表格|列表|清单|分点|markdown|json|大纲|步骤|标题|代码块|模板|示例|结构|要点|段落|摘要|提纲|ppt|输出成"
                    + "|写成|排版|几段|字左右|字以内",
            Pattern.CASE_INSENSITIVE);

    /** 模糊信号：这些词越多，需求越虚 */
    public static final Pattern VAGUE = Pattern.compile(
            "弄|搞|那个|东西|随便|大概|不知道|搞个|弄个|搞一下|弄一下|看一下|帮我看看|随便写",
            Pattern.CASE_INSENSITIVE);

    public static int count(Pattern pattern, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Matcher matcher = pattern.matcher(text);
        int hits = 0;
        while (matcher.find()) {
            hits++;
        }
        return hits;
    }

    public static String first(Pattern pattern, String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    /** 提取身份短语，提取不到返回 null */
    public static String extractRole(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = ROLE_PHRASE.matcher(text);
        if (matcher.find()) {
            String role = clean(matcher.group(1));
            if (role != null) {
                return role;
            }
        }
        matcher = ROLE_ALT.matcher(text);
        if (matcher.find()) {
            return clean(matcher.group(1));
        }
        return null;
    }

    private static String clean(String raw) {
        String role = raw.trim().replaceAll("[的嘛呢吧啊呀哦来]$", "").trim();
        if (role.length() < 2 || role.length() > 20) {
            return null;
        }
        return role;
    }
}