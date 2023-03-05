package searchengine.exceptions;

import java.io.IOException;

public class SiteConfigAbsentException extends RuntimeException {
    public SiteConfigAbsentException(String domain) {
        super("Website <" + domain + "> is denied");
    }
}
