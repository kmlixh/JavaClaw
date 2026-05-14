package com.janyee.agent.infra.auth;

import com.janyee.agent.infra.persistence.repository.auth.OauthClientRepository;
import org.springframework.stereotype.Service;

/**
 * "应用启用状态"查询服务 —— 给嵌入端在初始化阶段(还没有 OAuth token)调一次,根据
 * 结果决定 AI 按钮是否显示。
 *
 * <p><b>双开关语义</b>(V40 起):</p>
 * <ul>
 *   <li>{@code status = ACTIVE/DISABLED} —— 功能开关。DISABLED 时 OAuth 直接
 *       拒发 token(见 {@code OauthProviderService.requireActiveClient}),整套
 *       chat / send / 工具调用全废。</li>
 *   <li>{@code displayEnabled = true/false} —— UI 显示开关。控制 xmap-ol-front
 *       的 AI 按钮 v-if。displayEnabled=false 时按钮藏起来,但<b>功能不受影响</b>
 *       —— 用户在 console 调 {@code __forceShowAi(true)} 强制显示后,依然能正常
 *       发消息(前提 status=ACTIVE)。</li>
 * </ul>
 *
 * <p><b>本方法返回 true 的条件</b>:client 存在 AND status=ACTIVE AND
 * displayEnabled=true。任何一项不满足都返回 false → 前端 AI 按钮隐藏。</p>
 *
 * <p>独立成 service(而非直接在 controller 里查 repo),原因有二:</p>
 * <ol>
 *   <li>{@code agent-web} 模块没有 {@code spring-data-jpa} 依赖,无法直接 import
 *       {@link OauthClientRepository}。query 逻辑必须落在 {@code agent-infra} 这一层。</li>
 *   <li>future-proof:如果以后还要叠加"租户级 disable"、"灰度名单"等开关,这里是
 *       唯一的判定函数,所有调用方拿到的开关一致。</li>
 * </ol>
 */
@Service
public class AppStatusService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final OauthClientRepository oauthClientRepository;

    public AppStatusService(OauthClientRepository oauthClientRepository) {
        this.oauthClientRepository = oauthClientRepository;
    }

    /**
     * 判定指定 OAuth client(嵌入应用)的 AI 按钮是否应该显示。
     *
     * <p>显示条件:client 存在 && status==ACTIVE && display_enabled=true。
     * 不存在 / status≠ACTIVE / display_enabled=false 都返回 false。</p>
     */
    public boolean isAppEnabled(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return false;
        }
        return oauthClientRepository.findById(clientId.trim())
                .map(c -> STATUS_ACTIVE.equalsIgnoreCase(c.getStatus()) && c.isDisplayEnabled())
                .orElse(false);
    }
}
