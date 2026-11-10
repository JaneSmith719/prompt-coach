package com.promptcoach.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link CoachProperties} 注册成配置 Bean。
 */
@Configuration
@EnableConfigurationProperties(CoachProperties.class)
public class CoachConfig {
}