package searchengine.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class SnippetItem implements Serializable {
    private String site;
    private String siteName;
    private String uri;
    private String title;
    private String snippet;
    private double relevance;
}
