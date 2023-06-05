package searchengine.services.interfaces;

import searchengine.model.Page;

import java.util.Map;

public interface LemmaService {
    void saveLemmas(Page page);
    void decrementLemmaFrequencyOrDelete(Page page);
    String getFirstNormalForm(String word);
    String[] splitToWords(String text);
    Map<String, Integer> collectLemmas(String text);
}
