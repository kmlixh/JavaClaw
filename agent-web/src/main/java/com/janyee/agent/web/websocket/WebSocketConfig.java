package com.janyee.agent.web.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

@Configuration
public class WebSocketConfig {

    @Bean
    public HandlerMapping webSocketMapping(
            GlobalEventsWebSocketHandler globalEventsWebSocketHandler,
            BridgeWebSocketHandler bridgeWebSocketHandler
    ) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        // /ws/events - 用户级长连接,登录后建一条,按权限接收所有可见 session 的事件流。
        //              取代了原来的 /ws/chat(已删)——chat 那条耦合"发消息 + 接收"两件事,
        //              新架构发消息走 POST /api/chat/send,WS 只负责接收。
        // /ws/bridge - 嵌入式宿主调用桥,跟事件流无关,沿用。
        mapping.setUrlMap(Map.of(
                "/ws/events", globalEventsWebSocketHandler,
                "/ws/bridge", bridgeWebSocketHandler
        ));
        mapping.setOrder(-1);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
