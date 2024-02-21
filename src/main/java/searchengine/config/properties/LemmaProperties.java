package searchengine.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "search")
public class LemmaProperties {
    private String language = "english";
    private List<String> englishParticles;
    private List<String> russianParticles;
}
