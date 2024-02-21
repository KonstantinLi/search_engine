package searchengine.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "site")
@Getter
@Setter
public class Site implements Comparable<Site> {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Integer id;

    @Enumerated(value = EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "status_time", columnDefinition = "TIMESTAMP", nullable = false)
    private LocalDateTime statusTime;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(columnDefinition = "VARCHAR(255)", nullable = false)
    private String url;

    @Column(columnDefinition = "VARCHAR(255)", nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "VARCHAR(32)", nullable = false)
    private String language;

    @OneToMany(mappedBy = "site", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
    private List<Page> pages;

    @OneToMany(mappedBy = "site", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
    private List<Lemma> lemmas;

    @Override
    public int compareTo(Site o) {
        return name.compareTo(o.name);
    }
}
