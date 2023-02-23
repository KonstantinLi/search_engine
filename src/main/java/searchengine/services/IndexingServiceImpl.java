package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import searchengine.config.IndexingPropertiesList;
import searchengine.config.SiteConfig;
import searchengine.dto.recursive.PageRecursive;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {

    private final ApplicationContext applicationContext;
    private final IndexingPropertiesList propertiesList;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final Queue<Page> pageQueue = new ConcurrentLinkedQueue<>();
    private final Jedis jedis = new Jedis();
    private final Map<Site, ForkJoinPool> indexingSites =
            Collections.synchronizedMap(new HashMap<>());

    @Async
    @Override
    public void startIndexing() {
        deleteOldData();

        List<SiteConfig> sites = propertiesList.getSites();
        for (SiteConfig siteConfig: sites) {
            String name = siteConfig.getName();
            String url = siteConfig.getUrl();

            Site site = saveSite(name, url, Status.INDEXING, LocalDateTime.now());
            ForkJoinPool pool = applicationContext.getBean(ForkJoinPool.class);

            indexingSites.put(site, pool);
        }

        try {
            for (Site site : indexingSites.keySet())
                awaitSiteIndexing(site);
        } catch (Exception ex) {
            indexingSites.keySet().forEach(site -> failedSiteIfIndexing(site, ex.getMessage()));
        } finally {
            pageRepository.saveAllAndFlush(pageQueue);
            pageQueue.clear();
            indexingSites.clear();
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
    }

    @Override
    public boolean isIndexing() {
        return !indexingSites.isEmpty();
    }

    private void awaitSiteIndexing(Site site) throws InterruptedException {
        ForkJoinPool pool = indexingSites.get(site);
        buildSiteAsync(site, pool);
        boolean isNotTimeout = pool.awaitTermination(30, TimeUnit.MINUTES);

        if (!isNotTimeout) {
            pool.shutdownNow();
            pool.awaitTermination(1, TimeUnit.MINUTES);
            failedSiteIfIndexing(site, "TIMEOUT");
        } else if (site.getStatus() == Status.INDEXING) {
            setSiteStatus(site, Status.INDEXED);
        }
    }

    private void buildSiteAsync(Site site, ForkJoinPool pool) {
        RecursiveWebParser parser = applicationContext.getBean(RecursiveWebParser.class);

        parser.setSite(site);
        parser.setPool(pool);
        parser.setJedis(new Jedis());
        parser.setPageQueue(pageQueue);
        parser.setPageRecursive(createMainRecursivePage(site));
        parser.setForbiddenTypesList(propertiesList.getForbiddenUrlTypes());

        pool.execute(parser);
    }

    private PageRecursive createMainRecursivePage(Site site) {
        return new PageRecursive(site.getName(), site.getUrl() + "/");
    }

    private Site saveSite(String name, String url, Status status, LocalDateTime statusTime) {
        Site site = new Site();
        site.setName(name);
        site.setUrl(url);
        site.setStatusTime(statusTime);

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

    private void deleteOldData() {
        List<String> urls = propertiesList.getSites()
                .stream()
                .map(SiteConfig::getUrl)
                .toList();

        Set<Site> sites = new HashSet<>(siteRepository.findAllByUrlIsIn(urls));
        sites.addAll(siteRepository.findAllByStatus(Status.INDEXING));

        sites.forEach(site -> jedis.del(site.getName()));
        siteRepository.deleteAll(sites);
    }
}
