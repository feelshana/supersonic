package com.tencent.supersonic.common.config;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class ThreadPoolConfig {

    /**
     * BI 报表训练专用线程池：避免和在线请求/通用任务混用。 过载时使用 AbortPolicy 快速失败，防止回落到 Web 请求线程导致全站不可用。
     */
    @Bean("biAgentExecutor")
    public ThreadPoolExecutor getBiAgentExecutor() {
        return new ThreadPoolExecutor(8, 16, 60 * 3, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1024),
                new ThreadFactoryBuilder().setNameFormat("supersonic-biagent-pool-%d").build(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean("eventExecutor")
    public ThreadPoolExecutor getTaskEventExecutor() {
        return new ThreadPoolExecutor(4, 8, 60 * 3, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1024),
                new ThreadFactoryBuilder().setNameFormat("supersonic-event-pool-%d").build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean("commonExecutor")
    public ThreadPoolExecutor getCommonExecutor() {
        return new ThreadPoolExecutor(6, 12, 60 * 3, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1024),
                new ThreadFactoryBuilder().setNameFormat("supersonic-common-pool-%d").build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean("mapExecutor")
    public ThreadPoolExecutor getMapExecutor() {
        return new ThreadPoolExecutor(8, 16, 60 * 3, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                new ThreadFactoryBuilder().setNameFormat("supersonic-map-pool-%d").build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean("chatExecutor")
    public ThreadPoolExecutor getChatExecutor() {
        return new ThreadPoolExecutor(8, 16, 60 * 3, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1024),
                new ThreadFactoryBuilder().setNameFormat("supersonic-chat-pool-").build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
