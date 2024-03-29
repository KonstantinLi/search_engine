package searchengine.config;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
@ConditionalOnProperty(
        prefix = "search",
        name = "language",
        havingValue = "russian"
)
public class RussianLemmaConfig {
    @Bean
    public LuceneMorphology luceneMorphology() {
        try {
            return new RussianLuceneMorphology();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
