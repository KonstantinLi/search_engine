package searchengine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.concurrent.ForkJoinPool;

@Configuration
public class ForkJoinConfig {
    private static final int NUM_OF_THREADS = Runtime.getRuntime().availableProcessors();

    @Bean
    @Scope("prototype")
    public ForkJoinPool threadPoolTaskExecutor() {
        return new ForkJoinPool(
                NUM_OF_THREADS,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                null,
                true);
    }
}
