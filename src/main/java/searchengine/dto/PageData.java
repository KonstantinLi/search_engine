package searchengine.dto;

import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class PageData {
    @URL
    private String url;
}
