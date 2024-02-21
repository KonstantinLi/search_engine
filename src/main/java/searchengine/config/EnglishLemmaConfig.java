package searchengine.config;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
@ConditionalOnProperty(
        prefix = "search",
        name = "language",
        havingValue = "english",
        matchIfMissing = true
)
public class EnglishLemmaConfig {

    @Bean
    public LuceneMorphology luceneMorphology() {
        try {
            return new EnglishLuceneMorphology();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
