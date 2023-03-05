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
    long countBySite(Site site);

    Page findBySiteAndPath(Site site, String path);

    List<Page> findAllBySite(Site site, Pageable pageable);
}
