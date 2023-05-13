package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import searchengine.config.IndexingPropertiesList;
import searchengine.config.SiteConfig;
import searchengine.exceptions.SiteConfigAbsentException;
import searchengine.model.*;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DataManager {
    private final IndexingPropertiesList propertiesList;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaFinder lemmaFinder;
    private final Jedis jedis;

    @Value("${spring.jpa.properties.hibernate.jdbc.batch_size}")
    private int batchSize;

    public List<SiteConfig> getSitesInConfig() {
        return Optional.of(propertiesList.getSites()).orElse(new ArrayList<>());
    }

    public Site getSiteByUrlInConfig(String url) {
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

    public void updateSiteStatusTime(Site site) {
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

    public boolean checkTypeUrl(String url) {
        List<String> forbiddenTypesList = propertiesList.getForbiddenUrlTypes();
        return forbiddenTypesList == null || forbiddenTypesList.stream().noneMatch(url::contains);
    }

    public void collectAndSaveLemmas(Page page) {
        if (String.valueOf(page.getCode()).startsWith("4||5"))
            return;

        String clearHTML = lemmaFinder.clearHTML(page.getContent());
        Map<String, Integer> lemmaData = lemmaFinder.collectLemmas(clearHTML);

        List<Lemma> lemmas = lemmaRepository.findAllByLemmaIn(lemmaData.keySet());
        Queue<Index> indexQueue = new LinkedList<>();
        Site site = page.getSite();

        Iterator<Map.Entry<String, Integer>> iterator = lemmaData.entrySet().iterator();

        while (iterator.hasNext() && !Thread.currentThread().isInterrupted()) {
            Map.Entry<String, Integer> entry = iterator.next();
            String lemmaValue = entry.getKey();
            Integer rank = entry.getValue();

            Lemma lemma = findExistingLemma(lemmas, lemmaValue, site.getUrl());

            if (lemma == null) {
                lemma = new Lemma();
                lemma.setLemma(lemmaValue);
                lemma.setSite(site);
            }
            lemma.incrementFrequency();
            lemmaRepository.save(lemma);

            Index index = new Index();
            index.setLemma(lemma);
            index.setPage(page);
            index.setRank(Float.valueOf(rank));
            indexQueue.add(index);

            insertIndexesIfCountIsMoreThan(indexQueue, batchSize);
        }

        indexRepository.saveAllAndFlush(indexQueue);
    }

    private Lemma findExistingLemma(Collection<Lemma> lemmas, String lemmaValue, String url) {
        return lemmas.stream()
                .filter(lemma1 -> {
                    String lemmaSiteUrl = lemma1.getSite().getUrl();

                    return lemma1.getLemma().equals(lemmaValue)
                            && lemmaSiteUrl.equals(url);
                })
                .findAny().orElse(null);
    }

    public void decrementLemmaFrequencyOrDelete(Page page) {
        List<Lemma> lemmas = lemmaRepository.findAllByPage(page);
        for (Lemma lemma : lemmas) {
            int oldFrequency = lemma.getFrequency();
            if (oldFrequency > 1) {
                lemma.setFrequency(oldFrequency - 1);
                lemmaRepository.save(lemma);
            } else {
                lemmaRepository.delete(lemma);
            }
        }
    }

    public boolean deleteOldData(ExecutorService executor) throws InterruptedException {
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
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.MINUTES);
        }

        return isNotTimeout;
    }

    private void deleteSiteData(Site site) {
        while (!Thread.currentThread().isInterrupted() &&
                (lemmaRepository.countBySite(site) > 0 || pageRepository.countBySite(site) > 0)) {
            List<Lemma> lemmasToDelete = lemmaRepository.findAllBySite(site, PageRequest.of(0, batchSize));
            List<Page> pagesToDelete = pageRepository.findAllBySite(site, PageRequest.of(0, batchSize));

            indexRepository.deleteAllByLemmaIn(lemmasToDelete);
            indexRepository.deleteAllByPageIn(pagesToDelete);

            lemmaRepository.deleteAll(lemmasToDelete);
            pageRepository.deleteAll(pagesToDelete);
        }
    }

    public Site saveSite(String name, String url, String textError, Status status) {
        Site site = siteRepository.findByName(name);

        if (site == null) {
            site = new Site();
            site.setName(name);
            site.setUrl(url);
        }

        site.setStatusTime(LocalDateTime.now());
        site.setLastError(textError);
        site.setStatus(status);
        siteRepository.save(site);

        return site;
    }

    public void insertPagesIfCountIsMoreThan(Queue<Page> pageQueue, int size) {
        if (pageQueue.size() > size) {
            pageRepository.saveAll(pageQueue);
            pageQueue.forEach(this::collectAndSaveLemmas);
            pageQueue.clear();
        }
    }

    public void insertIndexesIfCountIsMoreThan(Queue<Index> indexQueue, int size) {
        if (indexQueue.size() > size) {
            indexRepository.saveAll(indexQueue);
            indexQueue.clear();
        }
    }
}
