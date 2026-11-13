package org.spring_web.spring_boot_service.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 耗时任务（AI 评审、成绩重算、导出）的独立线程池，避免拖垮 Web 请求线程。
 *
 * <p>同时打开定时任务：评审看门狗（{@code ReviewWatchdog}）需要它把卡住的 RUNNING 收敛成 FAILED。</p>
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    public static final String EXECUTOR = "sssTaskExecutor";

    @Bean(name = EXECUTOR)
    public Executor sssTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("sss-task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
