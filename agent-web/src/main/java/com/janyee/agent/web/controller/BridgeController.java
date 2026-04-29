package com.janyee.agent.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.janyee.agent.runtime.bridge.ExternalBridgeGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 嵌入场景下 host SDK 直接 HTTP 回写 invoke_result 的入口。
 *
 * <p>背景:LLM 触发 host.invoke 之后,后端从 {@code /ws/bridge} 把 invoke 推到 iframe;
 * iframe 转到父 SDK,父 SDK 调宿主方法拿到 result。原本 result 经
 * postMessage 走"父 SDK → iframe → /ws/bridge"折回,但 window.postMessage 这条通道
 * 被某些浏览器扩展(Vue Devtools 等)劫持后会丢消息,导致 invoke_result 永远到不了
 * 后端,LLM 端 15 秒后 bridge timeout。</p>
 *
 * <p>这条 HTTP 接口绕开了 postMessage —— SDK 拿着自己手上的 OAuth access_token,直接
 * POST 到这里,后端把 payload 喂给 {@link ExternalBridgeGateway#onClientMessage} 复用
 * 同一条对账逻辑(按 requestId 找 pending CompletableFuture 然后 complete)。</p>
 */
@RestController
@RequestMapping("/api/bridge")
public class BridgeController {

    private static final Logger log = LoggerFactory.getLogger(BridgeController.class);

    private final ExternalBridgeGateway gateway;
    private final ObjectMapper objectMapper;

    public BridgeController(ExternalBridgeGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    /**
     * 接收 host SDK 直接回传的 invoke 结果。请求体形如:
     * <pre>{
     *   "sessionId": "&lt;chat session id&gt;",
     *   "requestId": "&lt;来自 invoke 推送的 requestId&gt;",
     *   "success":   true | false,
     *   "result":    { ... } | null,
     *   "error":     "可选错误描述"
     * }</pre>
     */
    @PostMapping("/invoke-result")
    public ResponseEntity<?> invokeResult(@RequestBody JsonNode body) {
        String sessionId = body.path("sessionId").asText("");
        String requestId = body.path("requestId").asText("");
        if (sessionId.isBlank() || requestId.isBlank()) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "sessionId and requestId are required"));
        }
        // 重新组装成 onClientMessage 期待的 envelope。直接复用现有对账路径,不再写第二份。
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "invoke_result");
        envelope.put("requestId", requestId);
        envelope.put("success", body.path("success").asBoolean(false));
        if (body.has("result") && !body.get("result").isNull()) {
            envelope.set("result", body.get("result"));
        } else {
            envelope.set("result", objectMapper.createObjectNode());
        }
        if (body.hasNonNull("error")) {
            envelope.put("error", body.get("error").asText(""));
        }
        try {
            gateway.onClientMessage(sessionId, objectMapper.writeValueAsString(envelope));
            log.debug("bridge.invoke_result.http sessionId={}, requestId={}, success={}",
                    sessionId, requestId, envelope.path("success").asBoolean(false));
            return ResponseEntity.ok(java.util.Map.of("ok", true));
        } catch (Exception err) {
            log.warn("bridge.invoke_result.http_failed sessionId={}, requestId={}, err={}",
                    sessionId, requestId, err.toString());
            return ResponseEntity.internalServerError().body(java.util.Map.of(
                    "error", err.getMessage() == null ? err.toString() : err.getMessage()));
        }
    }
}
