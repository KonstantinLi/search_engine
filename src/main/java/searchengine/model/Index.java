package searchengine.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "index")
@IdClass(LemmaPage.class)
@Getter
@Setter
public class Index {
    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    private Lemma lemma;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    private Page page;

    @Column(nullable = false)
    private Float rank;
}
