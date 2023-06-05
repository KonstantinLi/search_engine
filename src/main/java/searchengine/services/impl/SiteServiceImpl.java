package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repositories.SiteRepository;
import searchengine.services.interfaces.SiteService;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SiteServiceImpl implements SiteService {
    private final SiteRepository siteRepository;

    @Override
    public void updateSiteStatusTime(Site site) {
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

    @Override
    public Site saveSite(String name, String url, String textError, Status status) {
        Site site = siteRepository.findByName(name);

        if (site == null) {
            site = new Site();
            site.setName(name);
            site.setUrl(url);
        }

        site.setStatusTime(LocalDateTime.now());
        site.setLastError(textError);
        site.setStatus(status);
        siteRepository.save(site);

        return site;
    }

    @Override
    public List<Site> getAllSites() {
        return siteRepository.findAll();
    }
}
