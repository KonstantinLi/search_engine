package searchengine.model;

import jakarta.persistence.Embeddable;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Embeddable
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor
public class LemmaPage implements Serializable {
    @ManyToOne
    private Lemma lemma;

    @ManyToOne
    private Page page;
}
