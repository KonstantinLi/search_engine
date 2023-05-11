package searchengine.exceptions;

public class IndexingException extends RuntimeException {
    public IndexingException() {
        super("Индексация уже запущена");
    }
}
