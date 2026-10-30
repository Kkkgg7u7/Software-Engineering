package com.werewolf.config;

import com.werewolf.websocket.SessionTrackingDecorator;
import com.werewolf.websocket.WsAuthChannelInterceptor;
import com.werewolf.websocket.WsDestinations;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * WebSocket + STOMP 配置。
 *
 * <p>使用内存简单代理（SimpleBroker）：符合“单体、不要微服务/Kafka/RabbitMQ”的约束，
 * 单机部署足够支撑课程项目规模。
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties appProperties;
    private final WsAuthChannelInterceptor authInterceptor;
    private final com.werewolf.websocket.RoomTopicAccessInterceptor deliveryAccess;

    public WebSocketConfig(AppProperties appProperties, WsAuthChannelInterceptor authInterceptor,
                           com.werewolf.websocket.RoomTopicAccessInterceptor deliveryAccess) {
        this.deliveryAccess = deliveryAccess;
        this.appProperties = appProperties;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 原生 WebSocket（前端推荐）：ws://host/ws?token=xxx
        //
        // 这里用 "*" 放行所有来源，是刻意的设计取舍：
        //   1) 别人的访问地址可能是 localhost、192.168.x.x、10.x.x.x，也可能是校园网/企业网的
        //      公网段地址（本机实测就是 222.197.x.x）或内网穿透域名，固定白名单必然漏；
        //   2) WebSocket 握手不像 CORS 需要回填 Access-Control-Allow-Origin，
        //      它只是"接受/拒绝"的布尔判定，用通配不会产生浏览器兼容问题；
        //   3) 安全边界在 STOMP CONNECT 鉴权和后续 SEND/SUBSCRIBE 目的地校验
        //      （见 WsAuthChannelInterceptor）。
        //
        // 如果将来要按域名收紧，把 "*" 换成具体域名列表即可，
        // 但要注意跨域 WebSocket 的防滥用主要靠 Token + 限流，而不是 Origin。
        registry.addEndpoint(WsDestinations.ENDPOINT)
                .setAllowedOriginPatterns("*")
                .addInterceptors(new TokenHandshakeInterceptor());

        // SockJS 兜底（某些代理环境原生 WS 不可用时）：http://host/ws
        registry.addEndpoint(WsDestinations.ENDPOINT)
                .setAllowedOriginPatterns("*")
                .addInterceptors(new TokenHandshakeInterceptor())
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /user 是 Spring 的用户目的地前缀，不能同时作为普通 broker 前缀：
        // 否则任何连接都能直接订阅 /user/{otherUserId}/queue/* 并收到私密消息。
        registry.enableSimpleBroker(WsDestinations.BROKER_PREFIX, WsDestinations.QUEUE_PREFIX);
        registry.setApplicationDestinationPrefixes(WsDestinations.APP_PREFIX);
        registry.setUserDestinationPrefix(WsDestinations.USER_PREFIX);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(deliveryAccess);
    }

    /**
     * 装饰 WebSocket 处理器，暴露"按 sessionId 主动关闭连接"的能力。
     *
     * <p>这是"单会话强制"能真正踢掉旧设备的关键：Spring 的 STOMP 层本身没有提供
     * 外部关闭任意会话的 API，必须从 WebSocket 层拿到底层 session 才能关闭。
     * {@link com.werewolf.websocket.WsSessionRegistry} 在检测到同账号新连接时，
     * 会通过 {@link com.werewolf.websocket.WsSessionCloser} 关掉旧连接。
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(SessionTrackingDecorator::new);
    }
}
