package searchengine.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class SearchResponse implements Serializable {
    private boolean result;
    private int count;
    private List<SnippetItem> data;
}
