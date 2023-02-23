package searchengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "indexing-settings")
public class IndexingPropertiesList {
    private List<SiteConfig> sites;
    private List<String> forbiddenUrlTypes;
}
