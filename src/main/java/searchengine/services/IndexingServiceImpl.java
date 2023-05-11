package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import searchengine.config.IndexingPropertiesList;
import searchengine.config.SiteConfig;
import searchengine.dto.PageData;
import searchengine.dto.recursive.PageRecursive;
import searchengine.exceptions.PageAbsentException;
import searchengine.exceptions.SiteConfigAbsentException;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {

    private final ApplicationContext applicationContext;
    private final IndexingPropertiesList propertiesList;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final Queue<Page> pageQueue = new ConcurrentLinkedQueue<>();
    private final Jedis jedis = new Jedis();
    private final Map<Site, ForkJoinPool> indexingSites =
            Collections.synchronizedMap(new HashMap<>());
    private final AtomicBoolean isIndexing = new AtomicBoolean();

    private ExecutorService deleteExecutor;
    private ExecutorService pageExecutor;

    @Async
    @Override
    public void startIndexing() {
        isIndexing.set(true);

        List<SiteConfig> sites = Optional.of(propertiesList.getSites()).orElse(new ArrayList<>());
        for (SiteConfig siteConfig: sites) {
            String name = siteConfig.getName();
            String url = siteConfig.getUrl();

            Site site = saveSite(name, url, null, Status.INDEXING);
            ForkJoinPool pool = applicationContext.getBean(ForkJoinPool.class);

            indexingSites.put(site, pool);
        }

        if (!awaitDataDeleting()) {
            flushAndClearResources();
            isIndexing.set(false);
            return;
        }

        try {
            for (Site site : indexingSites.keySet())
                awaitSiteIndexing(site);
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

        pageExecutor = applicationContext.getBean(ThreadPoolExecutor.class);
        Future<Void> future = pageExecutor.submit(pageIndexingCallable(pageRecursive));

        try {
            future.get();
        } catch (ExecutionException | InterruptedException ex) {
            ex.getCause().printStackTrace();
        }

        pageExecutor = null;
        isIndexing.set(false);
    }

    private Callable<Void> pageIndexingCallable(PageRecursive pageRecursive) {
        String mainUrl = pageRecursive.getMainUrl();
        String path = pageRecursive.getPath();
        Site site = getSiteByUrlInConfig(mainUrl);
        RecursiveWebParser recursiveWebParser = createRecursiveWebParser(site, path);

        return () -> {
            try {
                Page page = pageRepository.findBySiteAndPath(site, path);

                if (page == null) {
                    recursiveWebParser.parsePage();
                    page = pageRepository.save(recursiveWebParser.getPage());
                } else {
                    indexRepository.deleteAllByPage(page);
                    recursiveWebParser.decrementLemmaFrequencyOrDelete(page);
                }
                recursiveWebParser.collectAndSaveLemmas(page);

                return null;
            } catch (IOException ex) {
                throw new PageAbsentException(pageRecursive.getUrl());
            }
        };
    }

    private void awaitSiteIndexing(Site site) {
        ForkJoinPool pool = indexingSites.get(site);
        buildSiteAsync(site, pool);
        awaitSitePoolTermination(site, pool);
    }

    private void awaitSitePoolTermination(Site site, ExecutorService pool) {
        try {
            boolean isNotTimeout = pool.awaitTermination(5, TimeUnit.HOURS);

            if (!isNotTimeout) {
                shutdownNowAndAwait(pool);
                failedSiteIfIndexing(site, "TIMEOUT");
            } else if (site.getStatus() == Status.INDEXING) {
                site.setLastError(null);
                setSiteStatus(site, Status.INDEXED);
            }
        } catch (InterruptedException ex) {
            failedSiteIfIndexing(site, ex.getMessage());
        }
    }

    private boolean awaitDataDeleting() {
        try {
            deleteExecutor = applicationContext.getBean(ThreadPoolExecutor.class);
            if (!deleteOldData(deleteExecutor) ||
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

    private void buildSiteAsync(Site site, ForkJoinPool pool) {
        pool.execute(createRecursiveWebParser(site, "/", pool));
    }

    private RecursiveWebParser createRecursiveWebParser(Site site, String path, ForkJoinPool pool) {
        RecursiveWebParser parser = createRecursiveWebParser(site, path);
        parser.setPool(pool);
        return parser;
    }

    private RecursiveWebParser createRecursiveWebParser(Site site, String path) {
        RecursiveWebParser parser = applicationContext.getBean(RecursiveWebParser.class);

        parser.setSite(site);
        parser.setJedis(new Jedis());
        parser.setPageQueue(pageQueue);
        parser.setPageRecursive(createRecursivePage(site, path));
        parser.setForbiddenTypesList(propertiesList.getForbiddenUrlTypes());

        return parser;
    }

    private PageRecursive createRecursivePage(Site site, String path) {
        return new PageRecursive(site.getName(), site.getUrl() + path);
    }

    private Site getSiteByUrlInConfig(String url) {
        Optional<SiteConfig> siteConfigOptional = propertiesList
                .getSites()
                .stream()
                .filter(siteConfig -> siteConfig.getUrl().equals(url))
                .findFirst();

        if (siteConfigOptional.isPresent()) {
            SiteConfig siteConfig = siteConfigOptional.get();
            String name = siteConfig.getName();

            Site site = siteRepository.findByName(name);
            return site == null ? saveSite(name, url, null, Status.INDEXED) : site;
        } else {
            throw new SiteConfigAbsentException(url);
        }
    }

    public boolean siteIsAvailableInConfig(String url) {
        return propertiesList.getSites()
                .stream()
                .anyMatch(siteConfig -> siteConfig.getUrl().equals(url));
    }

    private Site saveSite(String name, String url, String error, Status status) {
        Site site = siteRepository.findByName(name);

        if (site == null) {
            site = new Site();
            site.setName(name);
            site.setUrl(url);
        }

        site.setStatusTime(LocalDateTime.now());
        site.setLastError(error);
        setSiteStatus(site, status);

        return site;
    }

    private void setSiteStatus(Site site, Status status) {
        site.setStatus(status);
        siteRepository.save(site);
    }

    private void failedSiteIfIndexing(Site site, String errorText) {
        if (site.getStatus() == Status.INDEXING) {
            site.setLastError(errorText);
            setSiteStatus(site, Status.FAILED);
        }
    }

    private void flushAndClearResources() {
        pageRepository.saveAllAndFlush(pageQueue);
        pageQueue.clear();
        indexingSites.clear();
    }

    private boolean deleteOldData(ExecutorService executor) throws InterruptedException {
        List<String> urls = propertiesList.getSites()
                .stream()
                .map(SiteConfig::getUrl)
                .toList();

        Set<Site> sites = new TreeSet<>(siteRepository.findAllByUrlIsIn(urls));
        sites.addAll(siteRepository.findAllByStatus(Status.INDEXING));

        executor.execute(() -> {
            sites.stream().peek(this::deleteSiteData).forEach(site -> jedis.del(site.getName()));
        });

        siteRepository.deleteAll(
                sites.stream()
                        .filter(site -> !urls.contains(site.getUrl()))
                        .collect(Collectors.toSet()));

        executor.shutdown();

        boolean isNotTimeout = executor.awaitTermination(5, TimeUnit.HOURS);
        if (!isNotTimeout) {
            shutdownNowAndAwait(executor);
        }

        return isNotTimeout;
    }

    private void deleteSiteData(Site site) {
        while (!Thread.currentThread().isInterrupted() &&
                (lemmaRepository.countBySite(site) > 0 || pageRepository.countBySite(site) > 0)) {
            List<Lemma> lemmasToDelete = lemmaRepository.findAllBySite(site, PageRequest.of(0, 500));
            List<Page> pagesToDelete = pageRepository.findAllBySite(site, PageRequest.of(0, 500));

            indexRepository.deleteAllByLemmaIn(lemmasToDelete);
            indexRepository.deleteAllByPageIn(pagesToDelete);

            lemmaRepository.deleteAll(lemmasToDelete);
            pageRepository.deleteAll(pagesToDelete);
        }
    }
}
