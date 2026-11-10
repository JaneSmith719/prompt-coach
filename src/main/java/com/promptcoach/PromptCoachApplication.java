package com.promptcoach;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PromptCoach 启动入口。
 *
 * <p>零依赖外网即可运行：没配置 AI_API_KEY 时使用本地规则引擎，
 * 配置后自动切换为大模型增强模式。</p>
 */
@SpringBootApplication
public class PromptCoachApplication {

    public static void main(String[] args) {
        SpringApplication.run(PromptCoachApplication.class, args);
    }
}