package searchengine.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.http.HttpStatus;

@Converter
public class HttpCodeAttributeConverter implements AttributeConverter<HttpStatus, Integer> {
    @Override
    public Integer convertToDatabaseColumn(HttpStatus attribute) {
        return attribute.value();
    }

    @Override
    public HttpStatus convertToEntityAttribute(Integer dbData) {
        return HttpStatus.resolve(dbData);
    }
}
