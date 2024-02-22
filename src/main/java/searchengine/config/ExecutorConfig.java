package searchengine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import searchengine.model.Page;

import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Configuration
public class ExecutorConfig {
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

    @Bean
    @Scope("prototype")
    public ThreadPoolExecutor executorService() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
    }

    @Bean
    public Queue<Page> pageQueue() {
        return new ConcurrentLinkedQueue<>();
    }

    @Bean
    public Lock pageQueueLocK() {
        return new ReentrantLock();
    }
}
