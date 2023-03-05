package searchengine.repositories;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;

import java.util.Collection;
import java.util.List;

@Repository
public interface LemmaRepository extends JpaRepository<Lemma, Integer> {
    int countBySite(Site site);

    List<Lemma> findAllByLemmaIn(Collection<String> values);

    List<Lemma> findAllBySite(Site site, Pageable pageable);

    @Query("FROM Lemma l " +
            "JOIN Index i ON i.lemma = l " +
            "JOIN Page p ON i.page = p " +
            "WHERE p = ?1")
    List<Lemma> findAllByPage(Page page);
}
