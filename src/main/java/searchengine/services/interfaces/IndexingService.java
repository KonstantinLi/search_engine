package searchengine.services.interfaces;

import searchengine.dto.PageData;

public interface IndexingService {
    void indexPage(PageData pageData);
    void startIndexing();
    void stopIndexing();
    boolean isIndexing();
}
