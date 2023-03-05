package searchengine.services;

import searchengine.dto.PageData;

public interface IndexingService {
    void indexPage(PageData pageData);
    void startIndexing();
    void stopIndexing();
    boolean isIndexing();
    boolean siteIsAvailableInConfig(String url);
}
