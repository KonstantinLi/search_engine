package searchengine.services.interfaces;

import searchengine.model.Page;

import java.util.Map;

public interface LemmaService {
    void saveLemmas(Page page);
    void decrementLemmaFrequencyOrDelete(Page page);
    String getFirstNormalForm(String word);
    Map<String, Integer> collectLemmas(String text);
}
