package com.promptcoach.web;

import com.promptcoach.model.CoachReport;
import com.promptcoach.model.CoachRequest;
import com.promptcoach.model.HistoryEntry;
import com.promptcoach.model.Template;
import com.promptcoach.service.CoachService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 对外 REST 接口。
 *
 * <pre>
 * POST /api/coach        诊断 + 改写（body: {text, enhance?, templateId?}）
 * GET  /api/templates    场景模板库
 * GET  /api/history      最近记录
 * DEL  /api/history      清空记录
 * GET  /api/meta         运行信息（是否已接大模型）
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class CoachController {

    private final CoachService coachService;

    public CoachController(CoachService coachService) {
        this.coachService = coachService;
    }

    @PostMapping("/coach")
    public CoachReport coach(@RequestBody(required = false) CoachRequest request) {
        return coachService.coach(request);
    }

    @GetMapping("/templates")
    public List<Template> templates() {
        return coachService.templates();
    }

    @GetMapping("/history")
    public List<HistoryEntry> history(@RequestParam(defaultValue = "10") int limit) {
        return coachService.history(Math.min(Math.max(limit, 1), 50));
    }

    @DeleteMapping("/history")
    public Map<String, Object> clearHistory() {
        coachService.clearHistory();
        return Map.of("cleared", true);
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return coachService.meta();
    }
}