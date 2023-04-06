package searchengine.dto;

import lombok.Data;

import java.util.Map;

@Data
public class SentenceLemma {
    private String text;
    private Map<String, Double> lemmaFrequency;
}
