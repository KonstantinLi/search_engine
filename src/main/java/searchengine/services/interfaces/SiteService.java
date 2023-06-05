package searchengine.services.interfaces;

import searchengine.model.Site;
import searchengine.model.Status;

import java.util.List;

public interface SiteService {
    void updateSiteStatusTime(Site site);
    Site saveSite(String name, String url, String textError, Status status);
    List<Site> getAllSites();
}
