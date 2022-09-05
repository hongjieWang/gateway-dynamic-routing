# gateway-dynamic-routing
SpringCloud网关动态路由

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



```
[
    {
        "id": "path_route_addr",
        "uri": "lb://jdd-app-center",
        "predicates":[
            {
                "name": "Path",
                "args": {
                    "pattern": "/app222s/**"
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

