package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LemmaFinder {
    private static final String[] PARTICLES = new String[] {"МЕЖД", "ПРЕДЛ", "СОЮЗ"};
    private final LuceneMorphology luceneMorphology;

    public String clearHTML(String html) {
        return Jsoup.clean(html, Safelist.none());
    }

    public Map<String, Integer> collectLemmas(String text) {
        Map<String, Integer> lemmas = new HashMap<>();
        String[] words = russianWords(text);

        for (String word : words) {
            if (word.isBlank() || isParticle(word))
                continue;

            List<String> normalForms = luceneMorphology.getNormalForms(word);
            if (normalForms.isEmpty()) {
                continue;
            }

            String firstNormalForm = normalForms.get(0);
            Integer repeat = lemmas.getOrDefault(firstNormalForm, 0);

            lemmas.put(firstNormalForm, repeat + 1);
        }

        return lemmas;
    }

    private String[] russianWords(String text) {
        return text.toLowerCase()
                .replaceAll("([^а-я\\s])", " ")
                .trim()
                .split("\\s+");
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
}
