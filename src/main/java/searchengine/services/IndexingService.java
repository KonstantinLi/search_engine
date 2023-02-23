package searchengine.services;

public interface IndexingService {
    void startIndexing();
    void stopIndexing();
    boolean isIndexing();
}
