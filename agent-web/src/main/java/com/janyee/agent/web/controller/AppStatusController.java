package com.janyee.agent.web.controller;

import com.janyee.agent.infra.auth.AppStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 应用启用状态查询入口 —— 给嵌入方(xmap-ol-front 等)做"AI 按钮要不要显示"的开关。
 *
 * <p>嵌入端在还没有 OAuth token 的初始化阶段就需要知道这个 client 是否被后端启用。所以本
 * 接口走 public 路径(参见 {@link com.janyee.agent.web.auth.JwtAuthWebFilter#isPublicPath}
 * 的白名单)+ CORS 放行(参见 {@link com.janyee.agent.web.auth.OauthCorsFilter}),
 * 不需要登录 / 也不需要鉴权。返回值是布尔型 + clientId 回显,不暴露任何 client 内部细节
 * (如 redirect_uris / secret hash 等)。</p>
 *
 * <p>启用判定委托给 {@link AppStatusService#isAppEnabled(String)};这里 controller 只
 * 做请求映射 + 响应组装,不掺业务逻辑。</p>
 */
@RestController
@RequestMapping("/api/apps")
public class AppStatusController {

    private final AppStatusService appStatusService;

    public AppStatusController(AppStatusService appStatusService) {
        this.appStatusService = appStatusService;
    }

    /**
     * 查询某个 OAuth client(等价于"嵌入应用")是否启用。
     *
     * <pre>
     *   GET /api/apps/{clientId}/enabled
     *   →  {"appId":"xmap","enabled":true}
     * </pre>
     *
     * @param clientId OAuth client id,例如 "xmap"
     */
    @GetMapping("/{clientId}/enabled")
    public Map<String, Object> isEnabled(@PathVariable("clientId") String clientId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("appId", clientId);
        response.put("enabled", appStatusService.isAppEnabled(clientId));
        return response;
    }
}
