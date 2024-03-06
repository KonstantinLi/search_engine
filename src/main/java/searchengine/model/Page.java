package searchengine.model;

import jakarta.persistence.Index;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpStatus;

@Entity
@Table(name = "page", indexes = {@Index(name = "path_index", columnList = "path")})
@Getter
@Setter
public class Page {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Integer id;

    @ManyToOne(optional = false)
    private Site site;

    @Column(columnDefinition = "VARCHAR(1000)", nullable = false)
    private String path;

    @Convert(converter = HttpCodeAttributeConverter.class)
    @Column(nullable = false)
    private HttpStatus code;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(nullable = false)
    private Integer length;
}
