package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
            ErrorResponse errorResponse = new ErrorResponse();
            errorResponse.setResult(false);
            errorResponse.setError("Индексация уже запущена");

            return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
        }

        indexingService.startIndexing();

        DefaultResponse response = new DefaultResponse();
        response.setResult(true);

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<DefaultResponse> stopIndexing() {
        if (!indexingService.isIndexing()) {
            ErrorResponse errorResponse = new ErrorResponse();
            errorResponse.setResult(false);
            errorResponse.setError("Индексация не запущена");
        }

        indexingService.stopIndexing();

        DefaultResponse response = new DefaultResponse();
        response.setResult(true);

        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
