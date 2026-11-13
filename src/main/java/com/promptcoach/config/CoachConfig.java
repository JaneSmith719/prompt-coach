package com.promptcoach.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 把 {@link CoachProperties} 注册成配置 Bean，并提供可替换的时钟，
 * 让增强限流的“整点窗口”可以在测试里被精确推进。
 */
@Configuration
@EnableConfigurationProperties(CoachProperties.class)
public class CoachConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}