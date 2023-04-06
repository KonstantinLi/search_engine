package searchengine.repositories;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.Page;
import searchengine.model.Site;

import java.util.List;

@Repository
public interface PageRepository extends JpaRepository<Page, Integer> {
    int countBySite(Site site);

    Page findBySiteAndPath(Site site, String path);

    List<Page> findAllBySite(Site site, Pageable pageable);

    @Query("SELECT DISTINCT p FROM Page p " +
            "JOIN Index i ON i.page = p " +
            "JOIN Lemma l ON i.lemma = l " +
            "WHERE l.lemma = ?1 AND p.site IN (?2)")
    List<Page> findAllByLemmaAndSiteIn(String lemma, List<Site> sites);
}
