package searchengine.dto.recursive;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;

@Getter
@Setter
@RequiredArgsConstructor
public class PageRecursive {
    private String name;
    private final String url;
    private HttpStatus code;
    private String content;

    public PageRecursive(String name, String url) {
        this.name = name;
        this.url = url;
    }

    public String getDomain() {
        return url.substring(url.indexOf("://") + 3, url.lastIndexOf(getPath()));
    }

    public String getPath() {
        String path = withoutProtocol();
        return path.substring(path.indexOf('/'));
    }

    public String getMainUrl() {
        return getProtocol() + getDomain();
    }

    private String getProtocol() {
        return url.substring(0, url.indexOf("://") + 3);
    }
    private String withoutProtocol() {
        return url.replaceAll("https?://", "");
    }
}
