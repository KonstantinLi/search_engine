package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.Site;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.interfaces.StatisticsService;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static searchengine.model.Status.FAILED;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;

    @Override
    public StatisticsResponse getStatistics() {
        TotalStatistics total = new TotalStatistics();

        List<DetailedStatisticsItem> detailed = new ArrayList<>();

        for (Site site : siteRepository.findAll()) {
            DetailedStatisticsItem item = getDetailedStatisticsItem(site);
            detailed.add(item);

            total.setPages(total.getPages() + item.getPages());
            total.setLemmas(total.getLemmas() + item.getLemmas());
        }

        detailed.sort(Comparator.comparingInt(DetailedStatisticsItem::getPages).reversed());

        total.setSites(detailed.size());
        total.setIndexing(sitesNotFailed());

        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);

        StatisticsResponse response = new StatisticsResponse();
        response.setStatistics(data);
        response.setResult(true);

        return response;
    }

    private DetailedStatisticsItem getDetailedStatisticsItem(Site site) {
        DetailedStatisticsItem item = new DetailedStatisticsItem();
        item.setName(site.getName());
        item.setUrl(site.getUrl());
        item.setPages(pageRepository.countBySite(site));
        item.setLemmas(lemmaRepository.countBySite(site));
        item.setStatus(String.valueOf(site.getStatus()));
        item.setError(site.getLastError());
        item.setStatusTime(Timestamp.valueOf(site.getStatusTime()).getTime());

        return item;
    }

    private boolean sitesNotFailed() {
        return siteRepository.findAll()
                .stream()
                .allMatch(site -> site.getStatus() != FAILED);
    }
}
