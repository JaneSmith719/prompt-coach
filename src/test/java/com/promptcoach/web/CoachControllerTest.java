package com.promptcoach.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口层冒烟测试：启动整个 Spring 上下文，走真实的 HTTP 语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CoachControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("POST /api/coach 返回诊断、三版提示词和建议")
    void coachReturnsReport() throws Exception {
        mockMvc.perform(post("/api/coach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"text\":\"帮我写个周报\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("local"))
                .andExpect(jsonPath("$.diagnosis.score").isNumber())
                .andExpect(jsonPath("$.diagnosis.grade").isString())
                .andExpect(jsonPath("$.variants.length()").value(3))
                .andExpect(jsonPath("$.structuredPrompt").isNotEmpty())
                .andExpect(jsonPath("$.elapsedMs").isNumber());
    }

    @Test
    @DisplayName("空输入返回 400 和中文提示")
    void blankTextReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/coach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("非法 JSON 也返回 400，而不是 500")
    void malformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/coach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    @DisplayName("模板库和运行信息可以正常读取")
    void templatesAndMetaAreAvailable() throws Exception {
        mockMvc.perform(get("/api/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThan(0)))
                .andExpect(jsonPath("$[0].id").isString())
                .andExpect(jsonPath("$[0].skeleton").isNotEmpty());

        mockMvc.perform(get("/api/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("PromptCoach"))
                .andExpect(jsonPath("$.llmAvailable").value(false));
    }

    @Test
    @DisplayName("历史记录可以读取，也能清空")
    void historyCanBeListedAndCleared() throws Exception {
        mockMvc.perform(post("/api/coach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"text\":\"帮我写个周报\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/history").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThan(0)));

        mockMvc.perform(delete("/api/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(true));

        mockMvc.perform(get("/api/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("首页静态页面可以直接访问")
    void indexPageIsServed() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PromptCoach")));

        // 访问根路径时由 WelcomePageHandlerMapping 转发到 index.html
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
    }

    @Test
    @DisplayName("不存在的接口返回 404 JSON，而不是服务器错误")
    void unknownApiReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }
}