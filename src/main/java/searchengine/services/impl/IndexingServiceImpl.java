package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import searchengine.config.SiteConfig;
import searchengine.dto.PageData;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.interfaces.IndexingService;
import searchengine.services.interfaces.SiteService;
import searchengine.services.utils.DataCleaner;
import searchengine.services.utils.PageIntrospect;
import searchengine.services.utils.PropertiesUtil;
import searchengine.services.utils.RecursiveWebParser;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(IndexingServiceImpl.class);

    private final SiteService siteService;
    private final DataCleaner dataCleaner;
    private final PropertiesUtil propertiesUtil;
    private final ApplicationContext applicationContext;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;

    private final Map<Site, ForkJoinPool> indexingSites =
            Collections.synchronizedMap(new HashMap<>());
    private final AtomicBoolean isIndexing = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Page> pageQueue = new ConcurrentLinkedQueue<>();

    private ExecutorService deleteExecutor;
    private ExecutorService pageExecutor;

    @Async
    @Override
    public void startIndexing() {
        isIndexing.set(true);

        List<SiteConfig> sites = propertiesUtil.getSitesInConfig();
        sites.forEach(this::putSiteToIndex);

        String siteNames = sites.stream().map(SiteConfig::getName).collect(Collectors.joining(", "));
        LOGGER.info("Start indexing: " + siteNames);

        if (!awaitDataDeleting()) {
            flushAndClearResources();
            isIndexing.set(false);
            return;
        }

        try {
            for (Map.Entry<Site, ForkJoinPool> entry: indexingSites.entrySet()) {
                awaitSiteIndexing(entry.getKey(), entry.getValue());
            }
            if (indexingSites.keySet().stream().allMatch(site -> site.getStatus() == Status.INDEXED)) {
                LOGGER.info("End indexing: " + siteNames);
            }
        } catch (Exception ex) {
            indexingSites.keySet().forEach(site -> failedSiteIfIndexing(site, ex.getMessage()));
            LOGGER.error("Indexing FAILED", ex);
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
                pageExecutor = null;
                LOGGER.info("Indexing page STOP");
                return;
            }

            indexingSites.keySet().forEach(site -> failedSiteIfIndexing(site, "Индексация остановлена пользователем"));
            LOGGER.info("Indexing STOP");

            if (deleteExecutor != null) {
                shutdownNowAndAwait(deleteExecutor);
            } else {
                Jedis jedis = applicationContext.getBean(Jedis.class);
                indexingSites.forEach((site, pool) -> {
                    try {
                        shutdownNowAndAwait(pool);
                        jedis.del(site.getName());
                    } catch (InterruptedException ex) {
                        failedSiteIfIndexing(site, ex.getMessage());
                        LOGGER.error("Exception is thrown", ex);
                    }
                });
            }
        } catch (InterruptedException ex) {
            LOGGER.error("Exception is thrown", ex);
        }
    }

    @Override
    public boolean isIndexing() {
        return isIndexing.get();
    }

    @Async
    @Override
    public void indexPage(PageData pageData) {
        isIndexing.set(true);

        String url = pageData.getUrl();
        LOGGER.info("Start indexing page: " + url);

        PageIntrospect pageIntrospect = new PageIntrospect(url);
        RecursiveWebParser recursiveWebParser = createRecursiveWebParser(pageIntrospect);

        pageExecutor = applicationContext.getBean(ThreadPoolExecutor.class);
        Future<Void> future = pageExecutor.submit(recursiveWebParser.pageIndexingCallable());

        try {
            future.get();
        } catch (ExecutionException | InterruptedException ex) {
            LOGGER.error("Exception is thrown", ex);
        }

        if (pageExecutor != null) {
            pageExecutor = null;
            LOGGER.info("End indexing page: " + url);
        }

        isIndexing.set(false);
    }

    private void awaitSiteIndexing(Site site, ForkJoinPool pool) {
        String name = site.getName();
        String url = site.getUrl();

        PageIntrospect pageIntrospect = new PageIntrospect(name, url + "/");
        pool.execute(createRecursiveWebParser(pageIntrospect, pool, site));
        try {
            boolean isNotTimeout = pool.awaitTermination(5, TimeUnit.HOURS);

            if (!isNotTimeout) {
                shutdownNowAndAwait(pool);
                failedSiteIfIndexing(site, "TIMEOUT");
                LOGGER.warn("Site " + name + "[" + url + "] indexing TIMEOUT");
            } else if (site.getStatus() == Status.INDEXING) {
                site.setLastError(null);
                site.setStatus(Status.INDEXED);
                siteRepository.save(site);
                LOGGER.debug("Site " + name + "[" + url + "] has been indexed");
            }
        } catch (InterruptedException ex) {
            failedSiteIfIndexing(site, ex.getMessage());
            LOGGER.error("Exception is thrown", ex);
        }
    }

    private boolean awaitDataDeleting() {
        try {
            deleteExecutor = applicationContext.getBean(ThreadPoolExecutor.class);
            List<SiteConfig> sites = propertiesUtil.getSitesInConfig();

            if (!dataCleaner.deleteOldData(deleteExecutor, sites)) {
                LOGGER.warn("Old data hasn't been deleted");
                return false;
            }

        } catch (InterruptedException | ExecutionException ex) {
            indexingSites.keySet().forEach(site -> failedSiteIfIndexing(site, ex.getMessage()));
            LOGGER.error("Indexing FAILED", ex);
            return false;

        } finally {
            deleteExecutor = null;
        }

        LOGGER.debug("Old data has been deleted successfully");
        return true;
    }

    private void shutdownNowAndAwait(ExecutorService pool) throws InterruptedException{
        pool.shutdownNow();
        pool.awaitTermination(1, TimeUnit.MINUTES);
    }

    private RecursiveWebParser createRecursiveWebParser(PageIntrospect pageIntrospect) {
        Site site = propertiesUtil.getSiteByUrlInConfig(pageIntrospect.getMainUrl());
        return createRecursiveWebParser(pageIntrospect, null, site);
    }

    private RecursiveWebParser createRecursiveWebParser(PageIntrospect pageIntrospect, ForkJoinPool pool, Site site) {
        RecursiveWebParser parser = applicationContext.getBean(RecursiveWebParser.class);

        parser.setSite(site);
        parser.setPool(pool);
        parser.setPageQueue(pageQueue);
        parser.setPage(pageIntrospect);

        return parser;
    }

    private void failedSiteIfIndexing(Site site, String errorText) {
        if (site.getStatus() == Status.INDEXING) {
            site.setLastError(errorText);
            site.setStatus(Status.FAILED);
            siteRepository.save(site);
        }
    }

    private void putSiteToIndex(SiteConfig siteConfig) {
        String name = siteConfig.getName();
        String url = siteConfig.getUrl();

        Site site = siteService.saveSite(name, url, null, Status.INDEXING);
        ForkJoinPool pool = applicationContext.getBean(ForkJoinPool.class);

        indexingSites.put(site, pool);
    }

    private void flushAndClearResources() {
        pageRepository.saveAllAndFlush(pageQueue);
        pageQueue.clear();
        indexingSites.clear();
        LOGGER.debug("Resources are flushed and cleared");
    }
}
