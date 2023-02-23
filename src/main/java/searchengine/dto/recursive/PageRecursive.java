package searchengine.dto.recursive;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@RequiredArgsConstructor
public class PageRecursive {
    private final String name;
    private final String url;
    private HttpStatus code;
    private String content;

    public String getDomain() {
        return url.substring(url.indexOf("://") + 3, url.lastIndexOf(getPath()));
    }

    public String getPath() {
        String path = withoutProtocol();
        return path.substring(path.indexOf('/'));
    }

    public String getMainUrl() {
        return getProtocol() + getDomain() + "/";
    }

    private String getProtocol() {
        return url.substring(0, url.indexOf("://") + 3);
    }
    private String withoutProtocol() {
        return url.replaceAll("https?://", "");
    }
}
