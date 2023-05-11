package searchengine.exceptions;

public class NotIndexingException extends RuntimeException {
    public NotIndexingException() {
        super("Индексация не запущена");
    }
}
