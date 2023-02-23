package searchengine.exceptions;

public class InvalidURLException extends RuntimeException {
    public InvalidURLException(String url) {
        super("Invalid url <" + url + ">");
    }
}
