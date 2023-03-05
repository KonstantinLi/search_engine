package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.PageData;
import searchengine.dto.statistics.DefaultResponse;
import searchengine.dto.statistics.ErrorResponse;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.StatisticsService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final StatisticsService statisticsService;
    private final IndexingService indexingService;

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

    @PostMapping(value = "/indexPage", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DefaultResponse> indexPage(@RequestBody PageData page) {
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

        indexingService.indexPage(page);

        DefaultResponse response = new DefaultResponse();
        response.setResult(true);

        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
