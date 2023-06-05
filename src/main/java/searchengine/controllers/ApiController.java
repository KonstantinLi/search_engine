package searchengine.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.hibernate.validator.constraints.URL;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import searchengine.annotations.AvailablePage;
import searchengine.dto.PageData;
import searchengine.dto.SearchResponse;
import searchengine.dto.statistics.DefaultResponse;
import searchengine.dto.statistics.ErrorResponse;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.exceptions.IndexingException;
import searchengine.exceptions.NotIndexingException;
import searchengine.model.Site;
import searchengine.services.interfaces.IndexingService;
import searchengine.services.interfaces.SearchService;
import searchengine.services.interfaces.SiteService;
import searchengine.services.interfaces.StatisticsService;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@Validated
@RequiredArgsConstructor
public class ApiController {
    private final StatisticsService statisticsService;
    private final IndexingService indexingService;
    private final SearchService searchService;
    private final SiteService siteService;

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<DefaultResponse> startIndexing() {
        throwIfIndexing();
        indexingService.startIndexing();
        return defaultResponse();
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<DefaultResponse> stopIndexing() {
        throwIfNotIndexing();
        indexingService.stopIndexing();
        return defaultResponse();
    }

    @PostMapping(value = "/indexPage", consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    public ResponseEntity<DefaultResponse> indexPage(@Valid @AvailablePage PageData pageData, BindingResult bindingResult) {
        if (bindingResult.hasErrors())
            throw new RuntimeException(getErrorMessages(bindingResult));

        throwIfIndexing();
        indexingService.indexPage(pageData);

        return defaultResponse();
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam @NotEmpty(message = "Задан пустой поисковой запрос") String query,
            @RequestParam(name = "site", required = false) @URL(
                    regexp = "^http(s)?://[-A-Za-z0-9.]+",
                    message = "URL адреса сайта должен соответствовать формату http(-s)://www.site.com")
            String mainUrl,
            @RequestParam(required = false, defaultValue = "0") @PositiveOrZero(message = "Значение offset должно быть больше или равно 0") Integer offset,
            @RequestParam(required = false, defaultValue = "20") @PositiveOrZero(message = "Значение limit должно быть больше или равно 0") Integer limit) {

        List<Site> sites = siteService.getAllSites()
                .stream()
                .filter(site -> mainUrl == null || site.getUrl().equals(mainUrl))
                .toList();

        SearchResponse searchResponse = searchService.search(query, sites, offset, limit);
        return new ResponseEntity<>(searchResponse, HttpStatus.OK);
    }

    private ResponseEntity<DefaultResponse> defaultResponse() {
        DefaultResponse defaultResponse = new DefaultResponse();
        defaultResponse.setResult(true);
        return new ResponseEntity<>(defaultResponse, HttpStatus.OK);
    }

    private void throwIfIndexing() {
        if (indexingService.isIndexing())
            throw new IndexingException();
    }

    private void throwIfNotIndexing() {
        if (!indexingService.isIndexing())
            throw new NotIndexingException();
    }

    private String getErrorMessages(BindingResult bindingResult) {
        return bindingResult.getAllErrors()
                .stream()
                .map(ObjectError::getDefaultMessage)
                .collect(Collectors.joining(". "));
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(value = {RuntimeException.class})
    public ErrorResponse handleException(RuntimeException ex) {
        return ErrorResponse.build(ex.getMessage());
    }
}
