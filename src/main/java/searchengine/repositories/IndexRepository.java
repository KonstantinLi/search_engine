package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;


@Repository
public interface IndexRepository extends JpaRepository<Index, Integer> {
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM Index i WHERE i.lemma = ?1")
    void deleteAllByLemma(Lemma lemma);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM Index i WHERE i.page = ?1")
    void deleteAllByPage(Page page);
}
