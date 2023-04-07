package searchengine.exceptions;

public class SiteConfigAbsentException extends RuntimeException {
    public SiteConfigAbsentException(String domain) {
        super("Website <" + domain + "> is denied");
    }
}
