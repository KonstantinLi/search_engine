package searchengine.services.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import searchengine.config.properties.IndexingProperties;
import searchengine.config.properties.SiteConfig;
import searchengine.exceptions.SiteConfigAbsentException;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repositories.SiteRepository;
import searchengine.services.interfaces.SiteService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PropertiesUtil {
    private final IndexingProperties propertiesList;
    private final SiteRepository siteRepository;
    private final SiteService siteService;

    public boolean siteIsAvailableInConfig(String url) {
        return getSitesInConfig()
                .stream()
                .anyMatch(siteConfig -> siteConfig.getUrl().equals(url));
    }

    public List<SiteConfig> getSitesInConfig() {
        return Optional.of(propertiesList.getSites()).orElse(new ArrayList<>());
    }

    public List<SiteConfig> getSitesInConfig(String language) {
        return propertiesList.getSites()
                .stream()
                .filter(site -> site.getLanguage().equals(language))
                .toList();
    }

    public Site getSiteByUrlInConfig(String url) {
        Optional<SiteConfig> siteConfigOptional = propertiesList
                .getSites()
                .stream()
                .filter(siteConfig -> siteConfig.getUrl().equals(url))
                .findFirst();

        if (siteConfigOptional.isPresent()) {
            SiteConfig siteConfig = siteConfigOptional.get();
            String name = siteConfig.getName();
            String language = siteConfig.getLanguage();

            Site site = siteRepository.findByName(name);
            return site == null ? siteService.saveSite(name, url, language, null, Status.INDEXED) : site;
        } else {
            throw new SiteConfigAbsentException(url);
        }
    }

    public boolean checkTypeUrl(String url) {
        List<String> forbiddenTypesList = propertiesList.getForbiddenUrlTypes();
        return forbiddenTypesList == null || forbiddenTypesList.stream().noneMatch(url::contains);
    }
}
