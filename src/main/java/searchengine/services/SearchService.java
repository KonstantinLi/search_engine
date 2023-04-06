package searchengine.services;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import searchengine.dto.SearchResponse;
import searchengine.model.Site;

import java.util.List;

public interface SearchService {
    SearchResponse search(
            @NotNull String query,
            @NotNull List<Site> sites,
            @PositiveOrZero int offset,
            @PositiveOrZero int limit);
    List<Site> getAllSites();
    boolean siteHasAnIndex(String mainUrl);
}
