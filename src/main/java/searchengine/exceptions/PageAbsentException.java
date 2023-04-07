package searchengine.exceptions;

public class PageAbsentException extends RuntimeException {
    public PageAbsentException(String page) {
        super("Page <" + page + "> is unavailable");
    }
}
