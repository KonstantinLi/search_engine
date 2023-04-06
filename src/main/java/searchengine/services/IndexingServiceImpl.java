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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Async
    @Override
    public void startIndexing() {
        isIndexing.set(true);
        deleteOldData();

        List<SiteConfig> sites = propertiesList.getSites();
        for (SiteConfig siteConfig: sites) {
            String name = siteConfig.getName();
            String url = siteConfig.getUrl();

            Site site = saveSite(name, url, Status.INDEXING);
            ForkJoinPool pool = applicationContext.getBean(ForkJoinPool.class);

            indexingSites.put(site, pool);
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
        indexingSites.forEach((site, pool) -> {
            try {
                failedSiteIfIndexing(site, "Индексация остановлена пользователем");
                pool.shutdownNow();
                pool.awaitTermination(1, TimeUnit.MINUTES);
                jedis.del(site.getName());
            } catch (InterruptedException ex) {
                failedSiteIfIndexing(site, ex.getMessage());
            }
        });

        isIndexing.set(false);
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

        String mainUrl = pageRecursive.getMainUrl();
        String path = pageRecursive.getPath();

        ForkJoinPool pool = applicationContext.getBean(ForkJoinPool.class);
        Site site = getSiteByUrlInConfig(mainUrl);
        RecursiveWebParser recursiveWebParser = createRecursiveWebParser(site, path);

        pool.execute(() -> {
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
            } catch (IOException ex) {
                failedSiteIfIndexing(site, "Страница <" + mainUrl + path + "> недоступна");
            } finally {
                pool.shutdownNow();
            }
        });

        awaitPoolTermination(site, pool);
        isIndexing.set(false);
    }

    private void awaitSiteIndexing(Site site) {
        ForkJoinPool pool = indexingSites.get(site);
        buildSiteAsync(site, "/", pool);
        awaitPoolTermination(site, pool);
    }

    private void awaitPoolTermination(Site site, ForkJoinPool pool) {
        try {
            boolean isNotTimeout = pool.awaitTermination(5, TimeUnit.HOURS);

            if (!isNotTimeout) {
                pool.shutdownNow();
                pool.awaitTermination(1, TimeUnit.MINUTES);
                failedSiteIfIndexing(site, "TIMEOUT");
            } else if (site.getStatus() == Status.INDEXING) {
                site.setLastError(null);
                setSiteStatus(site, Status.INDEXED);
            }
        } catch (InterruptedException ex) {
            failedSiteIfIndexing(site, ex.getMessage());
        }
    }

    private void buildSiteAsync(Site site, String path, ForkJoinPool pool) {
        pool.execute(createRecursiveWebParser(site, path, pool));
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
        parser.setPageRecursive(createMainRecursivePage(site, path));
        parser.setForbiddenTypesList(propertiesList.getForbiddenUrlTypes());

        return parser;
    }

    private PageRecursive createMainRecursivePage(Site site, String path) {
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
            return saveSite(siteConfig.getName(), siteConfig.getUrl(), Status.INDEXING);
        } else {
            throw new SiteConfigAbsentException(url);
        }
    }

    public boolean siteIsAvailableInConfig(String url) {
        return propertiesList.getSites()
                .stream()
                .anyMatch(siteConfig -> siteConfig.getUrl().equals(url));
    }

    private Site saveSite(String name, String url, Status status) {
        Site site = siteRepository.findByName(name);

        if (site == null) {
            site = new Site();
            site.setName(name);
            site.setUrl(url);
        }

        site.setStatusTime(LocalDateTime.now());
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

    private void deleteOldData() {
        List<String> urls = propertiesList.getSites()
            .stream()
            .map(SiteConfig::getUrl)
            .toList();

        Set<Site> sites = new HashSet<>(siteRepository.findAllByUrlIsIn(urls));
        sites.addAll(siteRepository.findAllByStatus(Status.INDEXING));

        sites.stream().peek(this::deleteSiteData).forEach(site -> jedis.del(site.getName()));
        siteRepository.deleteAll(sites);
    }

    private void deleteSiteData(Site site) {
        while (lemmaRepository.countBySite(site) > 0 || pageRepository.countBySite(site) > 0) {
            List<Lemma> lemmasToDelete = lemmaRepository.findAllBySite(site, PageRequest.of(0, 500));
            List<Page> pagesToDelete = pageRepository.findAllBySite(site, PageRequest.of(0, 500));

            indexRepository.deleteAllByLemmaIn(lemmasToDelete);
            indexRepository.deleteAllByPageIn(pagesToDelete);

            lemmaRepository.deleteAll(lemmasToDelete);
            pageRepository.deleteAll(pagesToDelete);
        }
    }
}
