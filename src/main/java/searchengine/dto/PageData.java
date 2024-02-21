package searchengine.dto;

import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class PageData {
    @URL(regexp = "^http(s)?://[-a-zA-Z0-9.]+[-a-zA-Z0-9/]+",
            message = "Запрос содержит неправильный URL")
    private String url;
    private String language;
}
