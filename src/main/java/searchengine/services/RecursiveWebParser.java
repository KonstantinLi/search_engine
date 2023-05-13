package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import searchengine.config.JsoupConnectionConfig;
import searchengine.exceptions.InvalidURLException;
import searchengine.exceptions.PageAbsentException;
import searchengine.exceptions.WebParserInterruptedException;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.PageRepository;

import java.io.IOException;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.stream.Collectors;

@Component
@Scope("prototype")
@RequiredArgsConstructor
@Setter
class RecursiveWebParser extends RecursiveAction implements Cloneable {
    private final JsoupConnectionConfig jsoupConnectionConfig;
    private final IndexRepository indexRepository;
    private final PageRepository pageRepository;
    private final DataManager dataManager;
    private final Jedis jedis;

    @Value("${spring.jpa.properties.hibernate.jdbc.batch_size}")
    private int batchSize;
    private PageIntrospect pageRecursive;
    private Queue<Page> pageQueue;
    private ForkJoinPool pool;
    private Site site;


    @SneakyThrows(WebParserInterruptedException.class)
    @Override
    protected void compute() {
        try {
            Document doc = parsePage();
            dataManager.updateSiteStatusTime(site);

            synchronized (pageQueue) {
                Page page = getPage();
                if (page.getPath().length() <= 1000) {
                    pageQueue.add(page);
                }
                dataManager.insertPagesIfCountIsMoreThan(pageQueue, batchSize);
            }

            Collection<RecursiveWebParser> recursiveWebParsers =
                    getRecursiveWebParsers(validLinks(doc));

            for (RecursiveWebParser parser : recursiveWebParsers) {
                parser.fork();
                Thread.sleep(100);
            }

            recursiveWebParsers.forEach(RecursiveAction::join);
            shutDownPoolIfExecuted();

        } catch (IOException ignored) {
        } catch (InterruptedException ex) {
            throw new WebParserInterruptedException(pageRecursive.getDomain());
        }
    }

    public Document parsePage() throws IOException {
        String url = pageRecursive.getUrl();

        if (!isValidUrl(url)) {
            throw new InvalidURLException(url);
        }

        Connection.Response response = urlConnect(url);
        Document doc = response.parse();

        pageRecursive.setContent(doc.html());
        pageRecursive.setCode(HttpStatus.valueOf(response.statusCode()));

        return doc;
    }

    public Page getPage() {
        Page page = new Page();
        page.setPath(pageRecursive.getPath());
        page.setContent(pageRecursive.getContent());
        page.setCode(pageRecursive.getCode());
        page.setSite(site);

        return page;
    }

    public Callable<Void> pageIndexingCallable() {
        String mainUrl = pageRecursive.getMainUrl();
        String path = pageRecursive.getPath();
        Site site = dataManager.getSiteByUrlInConfig(mainUrl);

        return () -> {
            try {
                Page page = pageRepository.findBySiteAndPath(site, path);

                if (page == null) {
                    parsePage();
                    page = pageRepository.save(getPage());
                } else {
                    indexRepository.deleteAllByPage(page);
                    dataManager.decrementLemmaFrequencyOrDelete(page);
                }
                dataManager.collectAndSaveLemmas(page);

                return null;
            } catch (IOException ex) {
                throw new PageAbsentException(pageRecursive.getUrl());
            }
        };
    }

    private Collection<String> validLinks(Document doc) {
        String mainUrl = pageRecursive.getMainUrl() + "/";
        String url = pageRecursive.getUrl();
        String name = pageRecursive.getName();

        Elements links = doc.select("a");
        return links.stream()
                .map(elem -> elem.absUrl("href"))
                .filter(link -> link.startsWith(mainUrl)
                        && !link.equals(mainUrl)
                        && !link.equals(url))
                .filter(dataManager::checkTypeUrl)
                .filter(link -> {
                    synchronized (jedis) {
                        return jedis.sadd(name, link) != 0;
                    }
                })
                .collect(Collectors.toSet());
    }

    private Collection<RecursiveWebParser> getRecursiveWebParsers(Collection<String> links) {
        return links.stream()
                .map(link -> getChildParser(new PageIntrospect(pageRecursive.getName(), link)))
                .collect(Collectors.toSet());
    }

    private RecursiveWebParser getChildParser(PageIntrospect child) {
        try {
            RecursiveWebParser childParser = clone();
            childParser.setPageRecursive(child);
            childParser.setPageQueue(pageQueue);
            childParser.setPool(pool);
            childParser.setSite(site);
            return childParser;
        } catch (CloneNotSupportedException ex) {
            throw new RuntimeException(ex);
        }
    }

    private Connection.Response urlConnect(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(jsoupConnectionConfig.getUserAgent())
                .referrer(jsoupConnectionConfig.getReferrer())
                .timeout(jsoupConnectionConfig.getTimeout())
                .ignoreHttpErrors(jsoupConnectionConfig.isIgnoreHttpErrors())
                .followRedirects(jsoupConnectionConfig.isFollowRedirects())
                .execute();
    }

    private void shutDownPoolIfExecuted() {
        String mainUrl = pageRecursive.getMainUrl() + "/";
        String url = pageRecursive.getUrl();

        if (mainUrl.equals(url)) {
            jedis.del(pageRecursive.getName());
            pool.shutdownNow();
        }
    }

    private boolean isValidUrl(String url) {
        return url.matches("^(https?)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]");
    }

    @Override
    protected RecursiveWebParser clone() throws CloneNotSupportedException {
        return (RecursiveWebParser) super.clone();
    }
}
