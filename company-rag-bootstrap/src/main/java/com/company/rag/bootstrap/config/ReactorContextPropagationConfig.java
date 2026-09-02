package com.company.rag.bootstrap.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Reactor 线程上下文传播配置。
 *
 * <p>Spring AI Alibaba Graph 框架基于 Reactor Flux 执行 Agent 节点，节点运行在框架自有
 * 调度器线程（日志中为 pool-4-thread-N），默认不继承调用线程的 SLF4J MDC，导致这些节点日志
 * 的 traceId/spanId 为空、全链路追踪断裂。</p>
 *
 * <p>Reactor 3.5+ 的 {@link Hooks#enableAutomaticContextPropagation()} 配合 io.micrometer
 * :context-propagation 注册的 ThreadLocalAccessor，会在调度器切换线程时自动采样并恢复
 * traceId/spanId，使框架内部线程日志与主线程保持一致。</p>
 */
@Configuration
public class ReactorContextPropagationConfig {

    /**
     * 启动时启用 Reactor 自动上下文传播，须早于任何 Flux 订阅执行。
     */
    @PostConstruct
    public void enableReactorContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
    }
}