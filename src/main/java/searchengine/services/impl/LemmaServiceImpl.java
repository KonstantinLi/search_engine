package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.services.interfaces.LemmaService;

import java.util.*;

@Component
@RequiredArgsConstructor
public class LemmaServiceImpl implements LemmaService {
    private static final String[] PARTICLES = new String[] {"МЕЖД", "ПРЕДЛ", "СОЮЗ"};
    private final LuceneMorphology luceneMorphology;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final Queue<Index> indexQueue = new LinkedList<>();

    @Value("${spring.jpa.properties.hibernate.jdbc.batch_size}")
    private int batchSize;

    @Override
    public void saveLemmas(Page page) {
        if (String.valueOf(page.getCode()).startsWith("4||5"))
            return;

        String clearHTML = Jsoup.parse(page.getContent()).text();
        Map<String, Integer> lemmaData = collectLemmas(clearHTML);

        List<Lemma> lemmas = lemmaRepository.findAllByLemmaIn(lemmaData.keySet());
        Iterator<Map.Entry<String, Integer>> iterator = lemmaData.entrySet().iterator();

        while (iterator.hasNext() && !Thread.currentThread().isInterrupted()) {
            Map.Entry<String, Integer> entry = iterator.next();
            saveLemma(entry, lemmas, page);
            insertIndexesIfCountIsMoreThan(batchSize);
        }

        indexRepository.saveAllAndFlush(indexQueue);
    }

    private void saveLemma(Map.Entry<String, Integer> lemmaData, List<Lemma> existingLemmas, Page page) {
        String lemmaValue = lemmaData.getKey();
        Integer rank = lemmaData.getValue();

        Site site = page.getSite();
        Lemma lemma = findExistingLemma(existingLemmas, lemmaValue, site.getUrl());

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
    }

    @Override
    public Map<String, Integer> collectLemmas(String text) {
        Map<String, Integer> lemmas = new HashMap<>();
        String[] words = splitToWords(text);

        for (String word : words) {
            if (word.isBlank() || isParticle(word))
                continue;

            String firstNormalForm = getFirstNormalForm(word);

            if (firstNormalForm.isBlank())
                continue;

            Integer repeat = lemmas.getOrDefault(firstNormalForm, 0);
            lemmas.put(firstNormalForm, repeat + 1);
        }

        return lemmas;
    }

    @Override
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

    @Override
    public String getFirstNormalForm(String word) {
        if (word.isBlank())
            return word;

        List<String> normalForms = luceneMorphology.getNormalForms(word);
        if (normalForms.isEmpty()) {
            return "";
        }

        return normalForms.get(0);
    }

    @Override
    public String[] splitToWords(String text) {
        return text.toLowerCase()
                .replaceAll("([^а-я\\s])", " ")
                .trim()
                .split("\\s+");
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

    private boolean isParticle(String word) {
        List<String> wordBaseForms = luceneMorphology.getMorphInfo(word);
        return wordBaseForms.stream().anyMatch(this::hasParticleProperty);
    }

    private boolean hasParticleProperty(String wordBase) {
        wordBase = wordBase.toUpperCase();

        for (String property : PARTICLES) {
            if (wordBase.contains(property)) {
                return true;
            }
        }

        return false;
    }

    private void insertIndexesIfCountIsMoreThan(int size) {
        if (indexQueue.size() > size) {
            indexRepository.saveAll(indexQueue);
            indexQueue.clear();
        }
    }
}
