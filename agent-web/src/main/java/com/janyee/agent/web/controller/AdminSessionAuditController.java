package com.janyee.agent.web.controller;

import com.janyee.agent.infra.admin.AdminSessionAuditService;
import com.janyee.agent.runtime.admin.AdminRunReplay;
import com.janyee.agent.runtime.admin.AdminRunStep;
import com.janyee.agent.runtime.admin.AdminRunSummary;
import com.janyee.agent.runtime.admin.AdminSessionListResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 管理员会话列表 + run 复盘端点。权限走 {@link com.janyee.agent.infra.auth.SessionVisibility}
 * 三档:系统管理员看全;租户管理员看本租户;普通用户看自己。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminSessionAuditController {

    private final AdminSessionAuditService service;

    public AdminSessionAuditController(AdminSessionAuditService service) {
        this.service = service;
    }

    @GetMapping("/sessions")
    public AdminSessionListResponse listSessions(
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "appId", required = false) String appId,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size
    ) {
        return service.listSessions(userId, tenantId, appId, agentId,
                parseInstant(from), parseInstant(to), page, size);
    }

    @GetMapping("/sessions/{sessionId}/runs")
    public List<AdminRunSummary> listRunsForSession(@PathVariable String sessionId) {
        return service.listRunsForSession(sessionId);
    }

    @GetMapping("/runs/{runId}/replay")
    public AdminRunReplay replay(@PathVariable String runId) {
        return service.getRunReplay(runId);
    }

    /**
     * V42 起的行级复盘端点。
     * {@code category} 是逗号分隔的分类集合(ai,db,file,artifact,plan,tool-other,lifecycle),
     * 服务端 SQL 过滤后返回 —— 前端不再需要拉整段 JSON 自己 parse。
     * 老 run 没行级数据时,服务会即时拆 event_log_json 返回,前端无感。
     */
    @GetMapping("/runs/{runId}/steps")
    public List<AdminRunStep> listSteps(
            @PathVariable String runId,
            @RequestParam(value = "category", required = false) String category
    ) {
        List<String> categories = null;
        if (category != null && !category.isBlank()) {
            categories = new java.util.ArrayList<>();
            for (String c : category.split(",")) {
                String t = c.trim();
                if (!t.isEmpty()) categories.add(t);
            }
        }
        return service.listRunSteps(runId, categories);
    }

    private Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (Exception ignore) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(raw.trim()));
            } catch (Exception ignore2) {
                return null;
            }
        }
    }
}
