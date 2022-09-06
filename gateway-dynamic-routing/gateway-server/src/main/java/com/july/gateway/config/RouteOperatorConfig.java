package com.july.gateway.config;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.july.gateway.service.RouteOperator;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 路由配置类
 *
 * @author wanghongjie
 */
@Configuration
public class RouteOperatorConfig {
    @Bean
    public RouteOperator routeOperator(ObjectMapper objectMapper,
                                       RouteDefinitionWriter routeDefinitionWriter,
                                       ApplicationEventPublisher applicationEventPublisher) {

        return new RouteOperator(objectMapper,
                routeDefinitionWriter,
                applicationEventPublisher);
    }
}