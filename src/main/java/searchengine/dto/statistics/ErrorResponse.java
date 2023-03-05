package searchengine.dto.statistics;

import lombok.Data;

@Data
public class ErrorResponse extends DefaultResponse {
    private String error;

    public static ErrorResponse build(String message) {
        ErrorResponse errorResponse = new ErrorResponse();
        errorResponse.setResult(false);
        errorResponse.setError(message);

        return errorResponse;
    }
}
