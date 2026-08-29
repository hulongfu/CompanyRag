package com.company.rag.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 线程池配置属性
 * 用于配置 Agent 异步调用的线程池参数
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.thread-pool")
public class AgentThreadPoolProperties {

    /**
     * 核心线程数
     * 线程池中保持活跃的最小线程数
     */
    private int corePoolSize = 4;

    /**
     * 最大线程数
     * 线程池允许创建的最大线程数
     */
    private int maxPoolSize = 20;

    /**
     * 线程空闲超时时间（秒）
     * 非核心线程在空闲多久后被回收
     */
    private long keepAliveSeconds = 60;

    /**
     * 队列容量
     * 用于缓存待执行任务的队列大小
     */
    private int queueCapacity = 100;

    /**
     * 线程名称前缀
     * 用于标识线程所属的线程池
     */
    private String threadNamePrefix = "agent-timeout";
}
