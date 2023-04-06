package searchengine.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.PageData;
import searchengine.dto.SearchResponse;
import searchengine.dto.recursive.PageRecursive;
import searchengine.dto.statistics.DefaultResponse;
import searchengine.dto.statistics.ErrorResponse;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.model.Site;
import searchengine.services.IndexingService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final StatisticsService statisticsService;
    private final IndexingService indexingService;
    private final SearchService searchService;

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<DefaultResponse> startIndexing() {
        if (indexingService.isIndexing()) {
            return new ResponseEntity<>(
                    ErrorResponse.build("Индексация уже запущена"),
                    HttpStatus.BAD_REQUEST);
        }

        indexingService.startIndexing();

        DefaultResponse response = new DefaultResponse();
        response.setResult(true);

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<DefaultResponse> stopIndexing() {
        if (!indexingService.isIndexing()) {
            return new ResponseEntity<>(
                    ErrorResponse.build("Индексация не запущена"),
                    HttpStatus.BAD_REQUEST);
        }

        indexingService.stopIndexing();

        DefaultResponse response = new DefaultResponse();
        response.setResult(true);

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping(value = "/indexPage", consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    public ResponseEntity<DefaultResponse> indexPage(@Valid PageData pageData, BindingResult bindingResult) {
        if (bindingResult.hasErrors())
            return new ResponseEntity<>(
                    ErrorResponse.build(getErrorMessages(bindingResult)),
                    HttpStatus.BAD_REQUEST);

        String url = pageData.getUrl();
        if (!url.endsWith("/"))
            pageData.setUrl(url + "/");

        PageRecursive page = new PageRecursive(pageData.getUrl());
        if (!indexingService.siteIsAvailableInConfig(page.getMainUrl())) {
            return new ResponseEntity<>(
                    ErrorResponse.build("Данная страница находится за пределами сайтов, " +
                            "указанных в конфигурационном файле"),
                    HttpStatus.BAD_REQUEST);
        }

        if (indexingService.isIndexing()) {
            return new ResponseEntity<>(
                    ErrorResponse.build("Индексация уже запущена"),
                    HttpStatus.BAD_REQUEST);
        }

        indexingService.indexPage(pageData);

        DefaultResponse response = new DefaultResponse();
        response.setResult(true);

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String query,
            @RequestParam(name = "site", required = false) String mainUrl,
            @RequestParam(required = false, defaultValue = "0") Integer offset,
            @RequestParam(required = false, defaultValue = "20") Integer limit) {

        if (query == null || query.isBlank())
            return new ResponseEntity<>(
                    ErrorResponse.build("Задан пустой поисковой запрос"),
                    HttpStatus.BAD_REQUEST);

        if (mainUrl != null) {
            if (!mainUrl.matches("^http(s)?://[A-Za-z0-9.]+"))
                return new ResponseEntity<>(
                        ErrorResponse.build("URL адреса сайта должен соответствовать формату http(-s)://www.site.com"),
                        HttpStatus.BAD_REQUEST);

            if (!searchService.siteHasAnIndex(mainUrl))
                return new ResponseEntity<>(
                        ErrorResponse.build(String.format("Сайт <%s> ранее не индексировался", mainUrl)),
                        HttpStatus.BAD_REQUEST);
        }

        if (offset < 0)
            return new ResponseEntity<>(
                    ErrorResponse.build("Значение offset должно быть больше или равно 0"),
                    HttpStatus.BAD_REQUEST);

        if (limit < 0)
            return new ResponseEntity<>(
                    ErrorResponse.build("Значение limit должно быть больше или равно 0"),
                    HttpStatus.BAD_REQUEST);

        List<Site> sites = searchService.getAllSites()
                .stream()
                .filter(site -> mainUrl == null || site.getUrl().equals(mainUrl))
                .toList();

        SearchResponse searchResponse = searchService.search(query, sites, offset, limit);
        return new ResponseEntity<>(searchResponse, HttpStatus.OK);
    }

    private String getErrorMessages(BindingResult bindingResult) {
        return bindingResult.getAllErrors()
                .stream()
                .map(ObjectError::getDefaultMessage)
                .collect(Collectors.joining(". "));
    }
}
