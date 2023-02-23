package searchengine.dto.statistics;

import lombok.Data;

@Data
public class ErrorResponse extends DefaultResponse {
    private String error;
}
