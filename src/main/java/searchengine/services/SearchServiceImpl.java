package searchengine.services;

import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.text.BreakIterator;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Validated
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {
    private static final int MOST_POPULAR_LEMMAS = 100;
    private static final int LIMIT_SNIPPET_LENGTH = 300;
    private static final int LIMIT_SENTENCE_LENGTH = 120;

    private final IndexRepository indexRepository;
    private final LemmaRepository lemmaRepository;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final Jedis jedis = new Jedis();
    private final LemmaFinder lemmaFinder;

    private List<String> mostPopularLemmas;

    @Override
    public SearchResponse search(
            @NotNull String query,
            @NotNull List<Site> sites,
            @PositiveOrZero int offset,
            @PositiveOrZero int limit) {

        String siteName = sites.size() == 1 ? sites.get(0).getName() : "all";

        SearchResponse deserializedResponse = getResponse(query, siteName);
        if (deserializedResponse != null) {
            limitAndOffset(deserializedResponse, limit, offset);
            return deserializedResponse;
        }

        if (mostPopularLemmas == null) {
            mostPopularLemmas = findMostPopularLemmas(
                    produceLemmasWithAverageFrequency(lemmaRepository.findAll()));
        }

        Map<String, Double> lemmasInQuery = removeMostPopularLemmas(
                averageFrequencyOfLemmasInQuery(query));

        Map<Page, Double> relevance = sortPagesByRelevanceDescending(
                findAllPagesWithLemmas(lemmasInQuery, sites));

        List<SnippetItem> snippetItemList = snippetItemList(relevance, lemmasInQuery);

        SearchResponse searchResponse = new SearchResponse();
        searchResponse.setResult(true);
        searchResponse.setCount(snippetItemList.size());
        searchResponse.setData(snippetItemList);

        saveResponse(query, siteName, searchResponse);

        limitAndOffset(searchResponse, limit, offset);
        return searchResponse;
    }

    private void limitAndOffset(
            SearchResponse searchResponse,
            @PositiveOrZero int limit,
            @PositiveOrZero int offset) {

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
        String content = lemmaFinder.clearHTML(page.getContent());

        List<SentenceLemma> sentenceLemmaList = splitIntoSentences(content).stream()
                .map(sentence -> findLemmasInSentence(sentence, lemmaFrequency))
                .filter(dto -> dto.getLemmaFrequency() != null && !dto.getLemmaFrequency().isEmpty())
                .toList();

        List<String> sortedSentences = sortSentencesByLemmaRarityAndCount(sentenceLemmaList);

        StringBuilder builder = new StringBuilder();
        int sentenceNumber = 0;

        while (builder.length() < LIMIT_SNIPPET_LENGTH && sentenceNumber < sortedSentences.size()) {
            String sentence = sortedSentences.get(sentenceNumber++).trim();

            builder.append(limitSentence(sentence));
            builder.append(" ");
        }

        return builder.toString();
    }

    private String limitSentence(String sentence) {
        int length = sentence.length();

        if (length <= LIMIT_SENTENCE_LENGTH)
            return sentence;

        int startLemma = sentence.indexOf("<b>");
        int endLemma = sentence.indexOf("</b>") + 4;

        if (endLemma - startLemma > LIMIT_SENTENCE_LENGTH)
            return "..." + sentence.substring(startLemma, endLemma) + "...";

        int remainLength = LIMIT_SENTENCE_LENGTH - (endLemma - startLemma) / 2;

        int start = startLemma - remainLength < 0
                ? 0
                : sentence.indexOf(" ", startLemma - remainLength) + 1;
        int end = endLemma + remainLength > length
                ? length
                : sentence.substring(endLemma, endLemma + remainLength).lastIndexOf(" ") + endLemma;

        String cropped = sentence.substring(start, end);
        if (start > 0)
            cropped = "..." + cropped;
        if (end < length)
            cropped += "...";

        return cropped;
    }

    private SentenceLemma findLemmasInSentence(String sentence, Map<String, Double> lemmaFrequency) {
        Map<String, Double> lemmasInSentence = new HashMap<>();
        String[] words = Arrays.stream(lemmaFinder.russianWords(sentence)).distinct().toArray(String[]::new);

        for (String word : words) {
            String lemma = lemmaFinder.getFirstNormalForm(word);
            if (!lemma.isBlank() && lemmaFrequency.containsKey(lemma)) {
                lemmasInSentence.put(lemma, lemmaFrequency.get(lemma));
                sentence = StringUtils.replaceIgnoreCase(sentence, word, "<b>" + word + "</b>");
            }
        }

        SentenceLemma sentenceLemma = new SentenceLemma();
        sentenceLemma.setText(sentence);
        sentenceLemma.setLemmaFrequency(lemmasInSentence);

        return sentenceLemma;
    }

    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();

        BreakIterator iterator = BreakIterator.getSentenceInstance();
        iterator.setText(text);

        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            sentences.add(text.substring(start, end));
        }

        return sentences;
    }

    private List<String> sortSentencesByLemmaRarityAndCount(List<SentenceLemma> sentenceLemmaList) {
        return sentenceLemmaList.stream()
                .sorted(sentenceComparator())
                .map(SentenceLemma::getText)
                .toList();
    }

    private Comparator<SentenceLemma> sentenceComparator() {
        Comparator<SentenceLemma> compareByLemmaRarity = (o1, o2) -> {
            Map<String, Double> lemmaFrequency1 = o1.getLemmaFrequency();
            Map<String, Double> lemmaFrequency2 = o2.getLemmaFrequency();

            List<Double> sortedFrequencies1 = new ArrayList<>(lemmaFrequency1.values());
            List<Double> sortedFrequencies2 = new ArrayList<>(lemmaFrequency2.values());

            Collections.sort(sortedFrequencies1);
            Collections.sort(sortedFrequencies2);

            Iterator<Double> iterator1 = sortedFrequencies1.iterator();
            Iterator<Double> iterator2 = sortedFrequencies2.iterator();

            Double mostRareLemma1;
            Double mostRareLemma2;

            while (iterator1.hasNext() && iterator2.hasNext()) {
                mostRareLemma1 = iterator1.next();
                mostRareLemma2 = iterator2.next();

                int compare = Double.compare(mostRareLemma1, mostRareLemma2);
                if (compare != 0)
                    return compare;
            }

            return 0;
        };

        return compareByLemmaRarity.thenComparing(
                dto -> dto.getLemmaFrequency().size(),
                Comparator.reverseOrder());
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
                .limit(MOST_POPULAR_LEMMAS)
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
        byte[] serializedResponse = serialize(searchResponse);
        jedis.hset("query: ".concat(site).getBytes(), query.getBytes(), serializedResponse);
    }

    private SearchResponse getResponse(String query, String site) {
        byte[] serializedResponse = jedis.hget("query: ".concat(site).getBytes(), query.getBytes());
        return (SearchResponse) deserialize(serializedResponse);
    }

    private byte[] serialize(Object object) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(object);

            return baos.toByteArray();
        } catch (Exception ignored) {}

        return null;
    }

    private Object deserialize(byte[] bytes) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            ObjectInputStream ois = new ObjectInputStream(bais);
            return ois.readObject();
        } catch (Exception ignored) {}

        return null;
    }

    public boolean siteHasAnIndex(String mainUrl) {
        return siteRepository.existsByUrl(mainUrl);
    }

    public List<Site> getAllSites() {
        return siteRepository.findAll();
    }

    private String getTitle(Page page) {
        return Jsoup.parse(page.getContent()).title();
    }

    @PreDestroy
    private void clearCache() {
        getAllSites().forEach(site -> jedis.del("query: ".concat(site.getName())));
        jedis.del("query: all");
    }
}
