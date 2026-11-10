package com.promptcoach.llm;

/**
 * 调用大模型失败时抛出，由上层捕获并降级为本地规则。
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}