package com.promptcoach.llm;

/**
 * 大模型调用抽象。
 *
 * <p>之所以抽成接口：单元测试里可以塞一个假实现，
 * 不联网也能测到「增强失败自动降级」这条分支。</p>
 */
public interface LlmClient {

    /** 是否配置齐全（有 Key、有地址、有模型名） */
    boolean isAvailable();

    /** 当前模型名，前端用来显示 */
    String model();

    /**
     * 一次性获取完整回答。
     *
     * @throws LlmException 网络错误、鉴权失败、返回为空等
     */
    String complete(String systemPrompt, String userPrompt);
}