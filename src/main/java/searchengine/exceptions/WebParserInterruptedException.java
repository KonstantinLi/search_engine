package searchengine.exceptions;

public class WebParserInterruptedException extends InterruptedException {
    public WebParserInterruptedException(String siteUrl) {
        super("<" + siteUrl + "> web parser interrupted.");
    }
}
