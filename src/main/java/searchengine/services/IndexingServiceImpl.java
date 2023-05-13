package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import searchengine.config.SiteConfig;
import searchengine.dto.PageData;
import searchengine.dto.recursive.PageRecursive;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {
    private final DataManager dataManager;
    private final ApplicationContext applicationContext;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;

    private final Queue<Page> pageQueue = new ConcurrentLinkedQueue<>();
    private final Map<Site, ForkJoinPool> indexingSites =
            Collections.synchronizedMap(new HashMap<>());
    private final AtomicBoolean isIndexing = new AtomicBoolean();

    private ExecutorService deleteExecutor;
    private ExecutorService pageExecutor;

    @Async
    @Override
    public void startIndexing() {
        isIndexing.set(true);

        List<SiteConfig> sites = dataManager.getSitesInConfig();
        for (SiteConfig siteConfig: sites) {
            String name = siteConfig.getName();
            String url = siteConfig.getUrl();

            Site site = dataManager.saveSite(name, url, null, Status.INDEXING);
            ForkJoinPool pool = applicationContext.getBean(ForkJoinPool.class);

            indexingSites.put(site, pool);
        }

        if (!awaitDataDeleting()) {
            flushAndClearResources();
            isIndexing.set(false);
            return;
        }

        try {
            for (Map.Entry<Site, ForkJoinPool> entry: indexingSites.entrySet())
                awaitSiteIndexing(entry.getKey(), entry.getValue());
        } catch (Exception ex) {
            indexingSites.keySet().forEach(site -> failedSiteIfIndexing(site, ex.getMessage()));
        } finally {
            flushAndClearResources();
            isIndexing.set(false);
        }
    }

    @Async
    @Override
    public void stopIndexing() {
        try {
            if (pageExecutor != null) {
                shutdownNowAndAwait(pageExecutor);
                return;
            }

            indexingSites.keySet().forEach(site -> failedSiteIfIndexing(site, "Индексация остановлена пользователем"));

            if (deleteExecutor != null) {
                shutdownNowAndAwait(deleteExecutor);
            } else {
                indexingSites.forEach((site, pool) -> {
                    try {
                        Jedis jedis = applicationContext.getBean(Jedis.class);
                        shutdownNowAndAwait(pool);
                        jedis.del(site.getName());
                    } catch (InterruptedException ex) {
                        failedSiteIfIndexing(site, ex.getMessage());
                    }
                });
            }
        } catch (InterruptedException ignored) {}
    }

    @Override
    public boolean isIndexing() {
        return isIndexing.get();
    }

    @Async
    @Override
    public void indexPage(PageData pageData) {
        isIndexing.set(true);

        PageRecursive pageRecursive = new PageRecursive(pageData.getUrl());
        RecursiveWebParser recursiveWebParser = createRecursiveWebParser(pageRecursive);

        pageExecutor = applicationContext.getBean(ThreadPoolExecutor.class);
        Future<Void> future = pageExecutor.submit(recursiveWebParser.pageIndexingCallable());

        try {
            future.get();
        } catch (ExecutionException | InterruptedException ex) {
            ex.getCause().printStackTrace();
        }

        pageExecutor = null;
        isIndexing.set(false);
    }

    @Override
    public boolean siteIsAvailableInConfig(String url) {
        List<SiteConfig> sites = dataManager.getSitesInConfig();
        return sites.stream().anyMatch(siteConfig -> siteConfig.getUrl().equals(url));
    }

    private void awaitSiteIndexing(Site site, ForkJoinPool pool) {
        PageRecursive pageRecursive = new PageRecursive(site.getName(), site.getUrl() + "/");
        pool.execute(createRecursiveWebParser(pageRecursive, pool, site));
        try {
            boolean isNotTimeout = pool.awaitTermination(5, TimeUnit.HOURS);

            if (!isNotTimeout) {
                shutdownNowAndAwait(pool);
                failedSiteIfIndexing(site, "TIMEOUT");
            } else if (site.getStatus() == Status.INDEXING) {
                site.setLastError(null);
                site.setStatus(Status.INDEXED);
                siteRepository.save(site);
            }
        } catch (InterruptedException ex) {
            failedSiteIfIndexing(site, ex.getMessage());
        }
    }

    private boolean awaitDataDeleting() {
        try {
            deleteExecutor = applicationContext.getBean(ThreadPoolExecutor.class);
            if (!dataManager.deleteOldData(deleteExecutor) ||
                    indexingSites.keySet().stream().allMatch(site -> site.getStatus() == Status.FAILED)) {
                return false;
            }

        } catch (InterruptedException ex) {
            indexingSites.keySet().forEach(site -> failedSiteIfIndexing(site, ex.getMessage()));
            return false;

        } finally {
            deleteExecutor = null;
        }

        return true;
    }

    private void shutdownNowAndAwait(ExecutorService pool) throws InterruptedException{
        pool.shutdownNow();
        pool.awaitTermination(1, TimeUnit.MINUTES);
    }

    private RecursiveWebParser createRecursiveWebParser(PageRecursive pageRecursive) {
        Site site = dataManager.getSiteByUrlInConfig(pageRecursive.getMainUrl());
        return createRecursiveWebParser(pageRecursive, null, site);
    }

    private RecursiveWebParser createRecursiveWebParser(PageRecursive pageRecursive, ForkJoinPool pool, Site site) {
        RecursiveWebParser parser = applicationContext.getBean(RecursiveWebParser.class);

        parser.setSite(site);
        parser.setPool(pool);
        parser.setPageQueue(pageQueue);
        parser.setPageRecursive(pageRecursive);

        return parser;
    }

    private void failedSiteIfIndexing(Site site, String errorText) {
        if (site.getStatus() == Status.INDEXING) {
            site.setLastError(errorText);
            site.setStatus(Status.FAILED);
            siteRepository.save(site);
        }
    }

    private void flushAndClearResources() {
        pageRepository.saveAllAndFlush(pageQueue);
        pageQueue.clear();
        indexingSites.clear();
    }
}
