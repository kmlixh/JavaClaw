package com.janyee.agent.web.controller;

import com.janyee.agent.infra.lab.AgentLabService;
import com.janyee.agent.infra.lab.LabGoalRefineService;
import com.janyee.agent.runtime.lab.AgentLabCreateRequest;
import com.janyee.agent.runtime.lab.AgentLabRefineRequest;
import com.janyee.agent.runtime.lab.AgentLabTaskDetail;
import com.janyee.agent.runtime.lab.AgentLabTaskView;
import com.janyee.agent.runtime.lab.LabRefineGoalRequest;
import com.janyee.agent.runtime.lab.LabRefineGoalResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Agent 实验室 REST 端点。权限由 {@link AgentLabService} 内部 {@link com.janyee.agent.infra.auth.PermissionGate}
 * 保证(要求 {@code agent.lab.use});这一层只做请求/响应映射。
 */
@RestController
@RequestMapping("/api/lab")
public class AgentLabController {

    private final AgentLabService agentLabService;
    private final LabGoalRefineService goalRefineService;

    public AgentLabController(AgentLabService agentLabService,
                              LabGoalRefineService goalRefineService) {
        this.agentLabService = agentLabService;
        this.goalRefineService = goalRefineService;
    }

    @PostMapping("/tasks")
    public AgentLabTaskView create(@RequestBody AgentLabCreateRequest req) {
        return agentLabService.create(req);
    }

    @GetMapping("/tasks")
    public List<AgentLabTaskView> list() {
        return agentLabService.list();
    }

    @GetMapping("/tasks/{taskId}")
    public AgentLabTaskDetail detail(@PathVariable String taskId) {
        return agentLabService.getDetail(taskId);
    }

    @DeleteMapping("/tasks/{taskId}/run")
    public Map<String, Object> cancel(@PathVariable String taskId) {
        boolean cancelled = agentLabService.cancel(taskId);
        return Map.of("cancelled", cancelled);
    }

    @PostMapping("/tasks/{taskId}/restart")
    public AgentLabTaskView restart(@PathVariable String taskId) {
        return agentLabService.restart(taskId);
    }

    /**
     * 改写约束规则后再次迭代:用户拿到 v1 调试结果后想改一改规则再 train 一遍。
     * 后端会重新派生测试场景并从 round-1 跑。
     */
    @PostMapping("/tasks/{taskId}/refine")
    public AgentLabTaskView refineConstraints(@PathVariable String taskId,
                                              @RequestBody AgentLabRefineRequest req) {
        return agentLabService.refineAndRestart(taskId,
                req.title(), req.goalDescription(), req.maxIterations(),
                req.constraintRules(), req.referenceDocuments(),
                req.metaLlmConfigId(), req.metaLlmModel(),
                req.testLlmConfigId(), req.testLlmModel());
    }

    /**
     * "AI 帮我完善目标描述":每轮一次 LLM 调用,无持久化。Service 直接返回 Mono,
     * 全程响应式 —— 不再有 blockLast。requireLabAccess() 仍在 controller 调用栈上
     * 同步执行(在反应式链组装前),所以 SecurityContextHolder 的 ThreadLocal 仍可用。
     */
    @PostMapping("/refine-goal")
    public Mono<LabRefineGoalResponse> refineGoal(@RequestBody LabRefineGoalRequest req) {
        return goalRefineService.refine(req);
    }
}
