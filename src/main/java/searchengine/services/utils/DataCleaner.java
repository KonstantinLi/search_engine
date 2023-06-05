package searchengine.services.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import searchengine.config.SiteConfig;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DataCleaner {
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final Jedis jedis;

    @Value("${spring.jpa.properties.hibernate.jdbc.batch_size}")
    private int batchSize;

    public boolean deleteOldData(ExecutorService executor, List<SiteConfig> siteConfigList) throws InterruptedException, ExecutionException {
        List<String> urls = siteConfigList.stream()
                .map(SiteConfig::getUrl)
                .toList();

        Set<Site> sites = new TreeSet<>(siteRepository.findAllByUrlIsIn(urls));
        sites.addAll(siteRepository.findAllByStatus(Status.INDEXING));

//        executor.execute(() -> sites.stream().peek(this::deleteSiteData).forEach(site -> jedis.del(site.getName())));

        Future<Boolean> future = executor.submit(() -> sites.stream().allMatch(this::deleteSiteData));
        boolean isDeleted = future.get();

        sites.forEach(site -> jedis.del(site.getName()));

        siteRepository.deleteAll(
                sites.stream()
                        .filter(site -> !urls.contains(site.getUrl()))
                        .collect(Collectors.toSet()));

        executor.shutdown();

        boolean isNotTimeout = executor.awaitTermination(5, TimeUnit.HOURS);
        if (!isNotTimeout) {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.MINUTES);
        }

        return isNotTimeout && isDeleted;
    }

    private boolean deleteSiteData(Site site) {
        while ((lemmaRepository.countBySite(site) > 0 || pageRepository.countBySite(site) > 0)) {
            if (Thread.currentThread().isInterrupted())
                return false;

            List<Lemma> lemmasToDelete = lemmaRepository.findAllBySite(site, PageRequest.of(0, batchSize));
            List<Page> pagesToDelete = pageRepository.findAllBySite(site, PageRequest.of(0, batchSize));

            indexRepository.deleteAllByLemmaIn(lemmasToDelete);
            indexRepository.deleteAllByPageIn(pagesToDelete);

            lemmaRepository.deleteAll(lemmasToDelete);
            pageRepository.deleteAll(pagesToDelete);
        }
        return true;
    }
}
