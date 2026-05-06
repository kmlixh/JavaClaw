package com.janyee.agent.infra.auth;

import com.janyee.agent.infra.persistence.repository.auth.OauthClientRepository;
import org.springframework.stereotype.Service;

/**
 * "应用启用状态"查询服务 —— 给嵌入端在初始化阶段(还没有 OAuth token)调一次,根据
 * 结果决定 AI 按钮是否显示。同时让运维通过把 oauth_client.status 切到 DISABLED 就能
 * 立刻关掉所有挂在该 client 下的入口,不需要改前端代码或重新部署。
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
     * 判定指定 OAuth client(嵌入应用)是否处于启用状态。
     *
     * <p>启用条件:client 存在 && status == "ACTIVE"。其他情况(不存在 / DISABLED /
     * null status / 异常)一律返回 false,前端按"未启用"渲染,避免错误展示。
     * V22/V25 schema 注释里把 status 取值范围定义为 ACTIVE | DISABLED,前端
     * dropdown 也用这两个值,以此保持一致。</p>
     */
    public boolean isAppEnabled(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return false;
        }
        return oauthClientRepository.findById(clientId.trim())
                .map(c -> STATUS_ACTIVE.equalsIgnoreCase(c.getStatus()))
                .orElse(false);
    }
}
