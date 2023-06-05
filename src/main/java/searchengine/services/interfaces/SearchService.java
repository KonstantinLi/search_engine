package searchengine.services.interfaces;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import searchengine.dto.SearchResponse;
import searchengine.model.Site;

import java.util.List;

public interface SearchService {
    SearchResponse search(
            @NotEmpty @NotNull String query,
            @NotNull List<Site> sites,
            @PositiveOrZero int offset,
            @PositiveOrZero int limit);
}
