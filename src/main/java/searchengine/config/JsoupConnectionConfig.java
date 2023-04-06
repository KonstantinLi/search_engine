package searchengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jsoup")
@Getter
@Setter
public class JsoupConnectionConfig {
    private String userAgent;
    private String referrer;
    private int timeout;
    private boolean ignoreHttpErrors;
    private boolean followRedirects;
}
