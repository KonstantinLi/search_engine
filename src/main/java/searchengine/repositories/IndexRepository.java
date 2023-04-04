package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;

import java.util.List;


@Repository
public interface IndexRepository extends JpaRepository<Index, Integer> {
    @Query("FROM Index i " +
            "JOIN Lemma l ON i.lemma = l " +
            "WHERE l.lemma = ?1")
    List<Index> findAllByLemma(String lemma);

    @Query("FROM Index i " +
            "JOIN Lemma l ON i.lemma = l " +
            "WHERE i.page = ?1 AND l.lemma = ?2")
    List<Index> findAllByPageAndLemma(Page page, String lemma);

    @Query("SELECT CASE WHEN COUNT(i) > 0 THEN true ELSE false END " +
            "FROM Index i JOIN Lemma l ON i.lemma = l " +
            "WHERE i.page = ?1 AND l.lemma = ?2")
    boolean existsByPageAndLemma(Page page, String lemma);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM Index i WHERE i.page = ?1")
    void deleteAllByPage(Page page);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM Index i WHERE i.lemma IN (?1)")
    void deleteAllByLemmaIn(List<Lemma> lemmas);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM Index i WHERE i.page IN (?1)")
    void deleteAllByPageIn(List<Page> pages);
}
