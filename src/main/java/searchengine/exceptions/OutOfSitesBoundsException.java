package searchengine.exceptions;

public class OutOfSitesBoundsException extends RuntimeException {
    public OutOfSitesBoundsException() {
        super("Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
    }
}
