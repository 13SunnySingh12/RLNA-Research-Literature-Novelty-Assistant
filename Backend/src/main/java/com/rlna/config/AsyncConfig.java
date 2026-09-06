package com.rlna.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * PDF processing runs here rather than on the request thread (Section 24).
 *
 * <p>The pool and its queue are both bounded. An unbounded queue would let a
 * batch upload accumulate work faster than a free-tier instance can retire it,
 * and CallerRunsPolicy pushes back on the submitter instead of silently
 * discarding a job whose database row already claims to be queued.
 */
@Configuration
public class AsyncConfig {

    @Bean("paperProcessingExecutor")
    Executor paperProcessingExecutor(@Value("${ASYNC_POOL_SIZE:2}") int poolSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(Math.max(50, poolSize * 25));
        executor.setThreadNamePrefix("paper-proc-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
