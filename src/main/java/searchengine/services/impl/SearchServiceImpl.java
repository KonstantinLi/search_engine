package searchengine.services.impl;

import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import redis.clients.jedis.Jedis;
import searchengine.dto.SearchResponse;
import searchengine.dto.SentenceLemma;
import searchengine.dto.SnippetItem;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.interfaces.SearchService;
import searchengine.services.interfaces.SiteService;
import searchengine.services.utils.SentenceUtil;
import searchengine.services.utils.Serializer;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Validated
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {
    private static final int LIMIT_SNIPPET_LENGTH = 300;
    private static final int MOST_POPULAR_LEMMAS_COUNT = 100;
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchServiceImpl.class);

    private final IndexRepository indexRepository;
    private final LemmaRepository lemmaRepository;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final LemmaServiceImpl lemmaFinder;
    private final SiteService siteService;
    private final Jedis jedis;

    private List<String> mostPopularLemmas;

    @Override
    public SearchResponse search(
            @NotEmpty @NotNull String query,
            @NotNull List<Site> sites,
            @PositiveOrZero int offset,
            @PositiveOrZero int limit) {

        LOGGER.info("Search request [limit=" + limit + ", offset=" + offset + "]: " + query);

        String siteName = sites.size() == 1 ? sites.get(0).getName() : "all";
        SearchResponse deserializedResponse = getResponse(query, siteName);

        if (deserializedResponse != null) {
            limitAndOffset(deserializedResponse, offset, limit);
            return deserializedResponse;
        }

        if (mostPopularLemmas == null) {
            mostPopularLemmas = findMostPopularLemmas(
                    produceLemmasWithAverageFrequency(lemmaRepository.findAll()));
        }

        SearchResponse searchResponse = makeResponse(query, offset, limit, sites);
        saveResponse(query, siteName, searchResponse);

        return searchResponse;
    }

    private SearchResponse makeResponse(String query, int offset, int limit, List<Site> sites) {
        Map<String, Double> lemmasInQuery = removeMostPopularLemmas(
                averageFrequencyOfLemmasInQuery(query));

        Map<Page, Double> relevance = sortPagesByRelevanceDescending(
                findAllPagesWithLemmas(lemmasInQuery, sites));

        List<SnippetItem> snippetItemList = snippetItemList(relevance, lemmasInQuery);

        SearchResponse searchResponse = new SearchResponse();
        searchResponse.setResult(true);
        searchResponse.setCount(snippetItemList.size());
        searchResponse.setData(snippetItemList);

        limitAndOffset(searchResponse, offset, limit);

        return searchResponse;
    }

    private void limitAndOffset(
            SearchResponse searchResponse,
            @PositiveOrZero int offset,
            @PositiveOrZero int limit) {

        List<SnippetItem> snippetItemList = searchResponse.getData()
                .stream()
                .skip(offset)
                .limit(limit)
                .toList();

        searchResponse.setData(snippetItemList);
    }

    private List<SnippetItem> snippetItemList(Map<Page, Double> pageRelevanceList, Map<String, Double> lemmaFrequency) {
        List<SnippetItem> snippetItemList = new ArrayList<>();

        for (Page page : pageRelevanceList.keySet()) {
            Site site = page.getSite();
            String title = getTitle(page);
            String snippet = makeSnippet(page, lemmaFrequency);

            SnippetItem snippetItem = new SnippetItem();
            snippetItem.setSite(site.getUrl());
            snippetItem.setSiteName(site.getName());
            snippetItem.setUri(page.getPath());
            snippetItem.setTitle(title);
            snippetItem.setSnippet(snippet);
            snippetItem.setRelevance(pageRelevanceList.get(page));

            snippetItemList.add(snippetItem);
        }

        return snippetItemList;
    }

    private String makeSnippet(Page page, Map<String, Double> lemmaFrequency) {
        String content = Jsoup.parse(page.getContent()).text();

        List<SentenceLemma> sentenceLemmaList = SentenceUtil.splitIntoSentences(content).stream()
                .map(sentence -> SentenceUtil.findLemmasInSentence(lemmaFinder, sentence, lemmaFrequency))
                .filter(dto -> dto.getLemmaFrequency() != null && !dto.getLemmaFrequency().isEmpty())
                .toList();

        List<String> sortedSentences = SentenceUtil.sortSentencesByLemmaRarityAndCount(sentenceLemmaList);

        StringBuilder builder = new StringBuilder();
        int sentenceNumber = 0;

        while (builder.length() < LIMIT_SNIPPET_LENGTH && sentenceNumber < sortedSentences.size()) {
            String sentence = sortedSentences.get(sentenceNumber++).trim();

            builder.append(SentenceUtil.limitSentence(sentence));
            builder.append(" ");
        }

        return builder.toString();
    }

    private Map<Page, Double> findAllPagesWithLemmas(Map<String, Double> averageFrequency, List<Site> sites) {
        if (averageFrequency.isEmpty())
            return Collections.emptyMap();

        List<String> sortedLemmas = sortLemmasByFrequency(averageFrequency);
        String mostRareLemma = sortedLemmas.stream().findFirst().orElse(null);

        List<Page> pages = pageRepository.findAllByLemmaAndSiteIn(mostRareLemma, sites);

        return computeRelevance(pages, sortedLemmas);
    }

    private Map<Page, Double> computeRelevance(List<Page> pages, List<String> lemmas) {
        Map<Page, Double> absoluteRelevance = new HashMap<>();

        pages.forEach(page -> {
            double pageAbsoluteRank = absoluteRankOfLemmasInPage(page, lemmas);
            if (pageAbsoluteRank > 0)
                absoluteRelevance.put(page, pageAbsoluteRank);
        });

        if (!absoluteRelevance.isEmpty()) {
            double maxAbsoluteRelevance = Collections.max(absoluteRelevance.values());
            absoluteRelevance.replaceAll((key, value) -> value / maxAbsoluteRelevance);
        }

        return absoluteRelevance;
    }

    private double absoluteRankOfLemmasInPage(Page page, List<String> lemmas) {
        List<Index> indexes = new ArrayList<>();
        for (String lemma : lemmas) {
            List<Index> indexesByLemma = indexRepository.findAllByPageAndLemma(page, lemma);

            if (indexesByLemma.isEmpty())
                return 0.0;

            indexes.addAll(indexesByLemma);
        }

        return indexes.stream().mapToDouble(Index::getRank).sum();
    }

    private List<String> sortLemmasByFrequency(Map<String, Double> frequency) {
        return frequency.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<Page, Double> sortPagesByRelevanceDescending(Map<Page, Double> relevance) {
        return relevance.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (v1, v2) -> v1,
                        LinkedHashMap::new));
    }

    private Map<String, Double> removeMostPopularLemmas(Map<String, Double> averageFrequency) {
        if (mostPopularLemmas == null)
            return averageFrequency;

        return averageFrequency.entrySet()
                .stream()
                .filter(entry -> !mostPopularLemmas.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private List<String> findMostPopularLemmas(Map<String, Double> averageFrequency) {
        return averageFrequency.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .limit(MOST_POPULAR_LEMMAS_COUNT)
                .toList();
    }

    private Map<String, Double> averageFrequencyOfLemmasInQuery(String query) {
        List<Lemma> lemmasInQuery = lemmaRepository.findAllByLemmaIn(
                lemmaFinder.collectLemmas(query).keySet());

        return produceLemmasWithAverageFrequency(lemmasInQuery);
    }

    private Map<String, Double> produceLemmasWithAverageFrequency(List<Lemma> lemmas) {
        long totalSitesCount = siteRepository.count();

        Map<String, Double> totalFrequency = new HashMap<>();
        for (Lemma lemmaObject : lemmas) {
            String lemma = lemmaObject.getLemma();
            Double oldFrequency = totalFrequency.getOrDefault(lemma, 0.0);
            totalFrequency.put(lemma, oldFrequency + lemmaObject.getFrequency());
        }
        totalFrequency.replaceAll((lemma, frequency) -> frequency / totalSitesCount);

        return totalFrequency;
    }

    private void saveResponse(String query, String site, SearchResponse searchResponse) {
        try {
            byte[] serializedResponse = Serializer.serialize(searchResponse);
            jedis.hset("query: ".concat(site).getBytes(), query.getBytes(), serializedResponse);
        } catch (NullPointerException ex) {
            LOGGER.info("There's no cached query: " + query);
        } catch (Exception ex) {
            LOGGER.error("Exception is thrown", ex);
        }
    }

    private SearchResponse getResponse(String query, String site) {
        try {
            byte[] serializedResponse = jedis.hget("query: ".concat(site).getBytes(), query.getBytes());
            return (SearchResponse) Serializer.deserialize(serializedResponse);
        } catch (NullPointerException ex) {
            LOGGER.info("There's no cached query: " + query);
            return null;
        } catch (Exception ex) {
            LOGGER.error("Exception is thrown", ex);
            return null;
        }
    }

    private String getTitle(Page page) {
        return Jsoup.parse(page.getContent()).title();
    }

    @PreDestroy
    private void clearCache() {
        siteService.getAllSites().forEach(site -> jedis.del("query: ".concat(site.getName())));
        jedis.del("query: all");
    }
}
