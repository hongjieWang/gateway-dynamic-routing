# SpringCloud Gateway 基于nacos实现动态路由
![pc](https://img-1258527903.cos.ap-beijing.myqcloud.com/img/pc.jpg)

## 动态路由背景

在使用 Cloud Gateway 的时候，官方文档提供的方案总是基于配置文件配置的方式

- 代码方式

```java
@SpringBootApplication
public class DemogatewayApplication {
	@Bean
	public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
		return builder.routes()
			.route("path_route", r -> r.path("/get")
				.uri("http://httpbin.org"))
			.route("host_route", r -> r.host("*.myhost.org")
				.uri("http://httpbin.org"))
			.route("rewrite_route", r -> r.host("*.rewrite.org")
				.filters(f -> f.rewritePath("/foo/(?<segment>.*)", "/${segment}"))
				.uri("http://httpbin.org"))
			.route("hystrix_route", r -> r.host("*.hystrix.org")
				.filters(f -> f.hystrix(c -> c.setName("slowcmd")))
				.uri("http://httpbin.org"))
			.route("hystrix_fallback_route", r -> r.host("*.hystrixfallback.org")
				.filters(f -> f.hystrix(c -> c.setName("slowcmd").setFallbackUri("forward:/hystrixfallback")))
				.uri("http://httpbin.org"))
			.route("limit_route", r -> r
				.host("*.limited.org").and().path("/anything/**")
				.filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(redisRateLimiter())))
				.uri("http://httpbin.org"))
			.build();
	}
}
```

- 配置文件方式

```yaml
spring:
  jmx:
    enabled: false
  cloud:
    gateway:
      default-filters:
      - PrefixPath=/httpbin
      - AddResponseHeader=X-Response-Default-Foo, Default-Bar

      routes:
      # =====================================
      # to run server
      # $ wscat --listen 9000
      # to run client
      # $ wscat --connect ws://localhost:8080/echo
      - id: websocket_test
        uri: ws://localhost:9000
        order: 9000
        predicates:
        - Path=/echo
      # =====================================
      - id: default_path_to_httpbin
        uri: ${test.uri}
        order: 10000
        predicates:
        - Path=/**
```

Spring Cloud Gateway作为微服务的入口，需要尽量避免重启，而现在配置更改需要重启服务不能满足实际生产过程中的动态刷新、实时变更的业务需求，所以我们需要在Spring Cloud Gateway运行时动态配置网关。

我们明确了目标需要实现动态路由，那么实现动态路由的方案有很多种，这里拿三种常见的方案来说明下：

- mysql + api 方案实现动态路由
- redis + api 实现动态路由
- nacos 配置中心实现动态路由

前两种方案本质上是一种方案，只是数据存储方式不同，大体实现思路是这样，我们通过接口定义路由的增上改查接口，通过接口来修改路由信息，将修改后的数据存储到mysql或redis中，并刷新路由，达到动态更新的目的。

第三种方案相对前两种相对简单，我们使用nacos的配置中心，将路由配置放在nacos上，写个监听器监听nacos上配置的变化，将变化后的配置更新到GateWay应用的进程内。

我们下面采用第三种方案，因为网关未连接mysql,使用redis还有开发相应的api和对应的web，来配置路由信息，而我们目前没有开发web的需求，所以我们采用第三种方案。

## 架构设计思路

- 封装`RouteOperator`类，用来删除和增加gateway进程内的路由；
- 创建一个配置类`RouteOperatorConfig`,可以将`RouteOperator`作为bean对象注册到Spring环境中；
- 创建nacos配置监听器，监听nacos上配置变化信息，将变更的信息更新到进程中；

整体架构图如下：

![](https://img-1258527903.cos.ap-beijing.myqcloud.com/img/20220905222042.png)

## 源码

代码目录结构：

![](https://img-1258527903.cos.ap-beijing.myqcloud.com/img/20220905225654.png)

app-server-a、app-server-b 为测试服务，gateway-server为网关服务。

这里我们重点看下网关服务的实现；

![WX20220906-134621@2x](https://img-1258527903.cos.ap-beijing.myqcloud.com/img/WX20220906-134621@2x.png)![](

代码非常简单，主要配置类、监听器、路由更新机制。

### RouteOperator 动态路由更新服务

动态路由更新服务主要提供网关进程内删除、添加等操作。

该类主要有路由清除`clear`、路由添加`add`、路由发布到进程`publish`和更新全部`refreshAll`方法。其中`clear`、`add`、`publish`为`private`方法，对外提供的为`refreshAll`方法。

实现思路：先清空路由->添加全部路由->发布路由更新事件->完成。

具体内容我们看下面代码：

```java
package com.july.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态路由更新服务
 *
 * @author wanghongjie
 */
@Slf4j
public class RouteOperator {
    private ObjectMapper objectMapper;

    private RouteDefinitionWriter routeDefinitionWriter;

    private ApplicationEventPublisher applicationEventPublisher;

    private static final List<String> routeList = new ArrayList<>();

    public RouteOperator(ObjectMapper objectMapper, RouteDefinitionWriter routeDefinitionWriter, ApplicationEventPublisher applicationEventPublisher) {
        this.objectMapper = objectMapper;
        this.routeDefinitionWriter = routeDefinitionWriter;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * 清理集合中的所有路由，并清空集合
     */
    private void clear() {
        // 全部调用API清理掉
        try {
            routeList.forEach(id -> routeDefinitionWriter.delete(Mono.just(id)).subscribe());
        } catch (Exception e) {
            log.error("clear Route is error !");
        }
        // 清空集合
        routeList.clear();
    }

    /**
     * 新增路由
     *
     * @param routeDefinitions
     */
    private void add(List<RouteDefinition> routeDefinitions) {

        try {
            routeDefinitions.forEach(routeDefinition -> {
                routeDefinitionWriter.save(Mono.just(routeDefinition)).subscribe();
                routeList.add(routeDefinition.getId());
            });
        } catch (Exception exception) {
            log.error("add route is error", exception);
        }
    }

    /**
     * 发布进程内通知，更新路由
     */
    private void publish() {
        applicationEventPublisher.publishEvent(new RefreshRoutesEvent(routeDefinitionWriter));
    }

    /**
     * 更新所有路由信息
     *
     * @param configStr
     */
    public void refreshAll(String configStr) {
        log.info("start refreshAll : {}", configStr);
        // 无效字符串不处理
        if (!StringUtils.hasText(configStr)) {
            log.error("invalid string for route config");
            return;
        }
        // 用Jackson反序列化
        List<RouteDefinition> routeDefinitions = null;
        try {
            routeDefinitions = objectMapper.readValue(configStr, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            log.error("get route definition from nacos string error", e);
        }
        // 如果等于null，表示反序列化失败，立即返回
        if (null == routeDefinitions) {
            return;
        }
        // 清理掉当前所有路由
        clear();
        // 添加最新路由
        add(routeDefinitions);

        // 通过应用内消息的方式发布
        publish();

        log.info("finish refreshAll");
    }
}

```

### RouteConfigListener 路由变化监听器

监听器的主要作用监听nacos路由配置信息，获取配置信息后刷新进程内路由信息。

该配置类通过`@PostConstruct`注解，启动时加载`dynamicRouteByNacosListener`方法，通过nacos的host、namespace、group等信息，读取nacos配置信息。`addListener`接口获取到配置信息后，将配置信息交给`routeOperator.refreshAll`处理。

这里指定了数据ID为：`gateway-json-routes`;

```java
package com.july.gateway.listener;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.july.gateway.service.RouteOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * nacos监听器
 *
 * @author wanghongjie
 */
@Component
@Slf4j
public class RouteConfigListener {

    private String dataId = "gateway-json-routes";

    @Value("${spring.cloud.nacos.config.server-addr}")
    private String serverAddr;
    @Value("${spring.cloud.nacos.config.namespace}")
    private String namespace;
    @Value("${spring.cloud.nacos.config.group}")
    private String group;

    @Autowired
    RouteOperator routeOperator;

    @PostConstruct
    public void dynamicRouteByNacosListener() throws NacosException {
        log.info("gateway-json-routes dynamicRouteByNacosListener config serverAddr is {} namespace is {} group is {}", serverAddr, namespace, group);
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, serverAddr);
        properties.put(PropertyKeyConst.NAMESPACE, namespace);
        ConfigService configService = NacosFactory.createConfigService(properties);
        // 添加监听，nacos上的配置变更后会执行
        configService.addListener(dataId, group, new Listener() {
            @Override
            public void receiveConfigInfo(String configInfo) {
                // 解析和处理都交给RouteOperator完成
                routeOperator.refreshAll(configInfo);
            }

            @Override
            public Executor getExecutor() {
                return null;
            }
        });

        // 获取当前的配置
        String initConfig = configService.getConfig(dataId, group, 5000);

        // 立即更新
        routeOperator.refreshAll(initConfig);
    }
}
```

### RouteOperatorConfig 配置类

配置类非常简单，熟悉SpringBoot的都能理解；

```java
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
```

## 测试

启动nacos，这里使用本机测试；

在nacos中增加以下配置：

```yaml
[
  {
    "id": "app-server-a",
    "uri": "lb://app-server-a",
    "predicates": [
      {
        "name": "Path",
        "args": {
          "pattern": "/a/**"
        }
      }
    ],
    "filters": [
      {
        "name": "StripPrefix",
        "args": {
          "parts": "1"
        }
      }
    ]
  }
]
```

这里我们先将`app-server-a`添加到网关中。

![WX20220906-172207@2x](https://img-1258527903.cos.ap-beijing.myqcloud.com/img/WX20220906-172207@2x.png)

我们启动`app-server-a`、`app-server-b`和`gateway-server`;

我们启动网关可以看到正常拉去到配置信息：

![WX20220906-172835@2x](https://img-1258527903.cos.ap-beijing.myqcloud.com/img/WX20220906-172835@2x.png)

我们测试下服务A能否正常访问，这里网关的端口是8080；

我们访问：[127.0.0.1:8080/a/server-a](http://127.0.0.1:8080/a/server-a)

可以看到访问成功：

![WX20220906-173658@2x](https://img-1258527903.cos.ap-beijing.myqcloud.com/img/WX20220906-173658@2x.png)

我们不停止服务，新增路由访问服务B:

nacos配置如下：
```json
[
  {
    "id": "app-server-a",
    "uri": "lb://app-center-a",
    "predicates": [
      {
        "name": "Path",
        "args": {
          "pattern": "/a/**"
        }
      }
    ],
    "filters": [
      {
        "name": "StripPrefix",
        "args": {
          "parts": "1"
        }
      }
    ]
  },
  {
    "id": "app-server-b",
    "uri": "lb://app-center-b",
    "predicates": [
      {
        "name": "Path",
        "args": {
          "pattern": "/b/**"
        }
      }
    ],
    "filters": [
      {
        "name": "StripPrefix",
        "args": {
          "parts": "1"
        }
      }
    ]
  }
]
```

我们在浏览器中访问：[127.0.0.1:8080/b/server-b](http://127.0.0.1:8080/b/server-b)

![WX20220906-174738@2x](https://img-1258527903.cos.ap-beijing.myqcloud.com/img/WX20220906-174738@2x.png)

我们把/b/改成c在测试下；

![WX20220906-174903@2x](https://img-1258527903.cos.ap-beijing.myqcloud.com/img/WX20220906-174903@2x.png)

可以看到到使用c可以访问成功啦，在使用b访问，会出现404；

![WX20220906-174917@2x](https://img-1258527903.cos.ap-beijing.myqcloud.com/img/WX20220906-174917@2x.png)

我们使用[127.0.0.1:8080/actuator/gateway/routes](http://127.0.0.1:8080/actuator/gateway/routes)查看下当前路由。

![WX20220906-174943@2x](https://img-1258527903.cos.ap-beijing.myqcloud.com/img/WX20220906-174943@2x.png)

```json
[
  {
    "predicate": "Paths: [/a/**], match trailing slash: true",
    "route_id": "app-server-a",
    "filters": [
      "[[StripPrefix parts = 1], order = 1]"
    ],
    "uri": "lb://app-center-a",
    "order": 0
  },
  {
    "predicate": "Paths: [/c/**], match trailing slash: true",
    "route_id": "app-server-b",
    "filters": [
      "[[StripPrefix parts = 1], order = 1]"
    ],
    "uri": "lb://app-center-b",
    "order": 0
  }
]
```

至此，网关动态路由研发测试完成。

## 拓展

有些公司会在网关中增加限流，使用`RequestRateLimiter`组件，正常配置信息如下：

![WX20220906-175454@2x](https://img-1258527903.cos.ap-beijing.myqcloud.com/img/WX20220906-175454@2x.png)

那么动态路由中json应该这样配置：

```
[
    {
        "id": "server",
        "uri": "lb://jdd-server",
        "predicates":[
            {
                "name": "Path",
                "args": {
                    "pattern": "/server/**"
                }
            }
        ],
        "filters":[
            {
                "name":"StripPrefix",
                "args":{
                    "parts": "1"
                }
            },
            {
                "name":"RequestRateLimiter",
                "args":{
                    "redis-rate-limiter.replenishRate":"1000",
                     "redis-rate-limiter.burstCapacity":"1000",
                      "key-resolver":"#{@remoteAddrKeyResolver}"
                }
            }
        ]
    }
]
```

over!
