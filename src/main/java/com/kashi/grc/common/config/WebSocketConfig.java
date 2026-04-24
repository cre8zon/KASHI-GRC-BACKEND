package com.kashi.grc.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Spring STOMP WebSocket configuration.
 *
 * Connection endpoint: ws://host/ws  (SockJS fallback: http://host/ws)
 *
 * Topic rooms — frontend subscribes to these:
 *   /topic/instance/{workflowInstanceId}  → all events on a workflow instance
 *   /topic/step/{stepInstanceId}          → events on a specific step
 *   /topic/user/{userId}                  → personal notifications for a user
 *   /topic/artifact/{entityType}/{id}     → events on an artifact (assessment, engagement...)
 *
 * Publish prefix: /app (not used currently — server pushes only)
 *
 * NOTE: Add spring-boot-starter-websocket to pom.xml:
 *   <dependency>
 *     <groupId>org.springframework.boot</groupId>
 *     <artifactId>spring-boot-starter-websocket</artifactId>
 *   </dependency>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Enable simple in-memory broker for topic subscriptions
        registry.enableSimpleBroker("/topic", "/queue");
        // Prefix for messages from clients to server (future use)
        registry.setApplicationDestinationPrefixes("/app");
        // Prefix for user-specific messages
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // Allow all origins during development — tighten in production
                .setAllowedOriginPatterns("*")
                // SockJS fallback for environments that don't support native WebSocket
                .withSockJS();
    }
}