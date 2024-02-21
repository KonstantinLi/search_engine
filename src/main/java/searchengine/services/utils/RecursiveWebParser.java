package searchengine.services.utils;

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
import searchengine.exceptions.InvalidURLException;
import searchengine.exceptions.PageAbsentException;
import searchengine.exceptions.WebParserInterruptedException;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.PageRepository;
import searchengine.services.interfaces.LemmaService;
import searchengine.services.interfaces.SiteService;

import java.io.IOException;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.stream.Collectors;

@Component
@Scope("prototype")
@RequiredArgsConstructor
@Setter
public class RecursiveWebParser extends RecursiveAction implements Cloneable {
    private final PropertiesUtil propertiesUtil;
    private final IndexRepository indexRepository;
    private final PageRepository pageRepository;
    private final LemmaService lemmaService;
    private final SiteService siteService;
    private final Jedis jedis;

    @Value("${spring.jpa.properties.hibernate.jdbc.batch_size}")
    private int batchSize;
    private PageIntrospect page;
    private ConcurrentLinkedQueue<Page> pageQueue;
    private ForkJoinPool pool;
    private Site site;

    @SneakyThrows(WebParserInterruptedException.class)
    @Override
    protected void compute() {
        try {
            Document doc = parsePage();
            siteService.updateSiteStatusTime(site);

            synchronized (pageQueue) { // ensure other threads wait when a thread captured the monitor provide multi-insert operation
                Page page = getPage();
                if (page.getPath().length() <= 1000) {
                    pageQueue.add(page);
                }
                insertPagesIfCountIsMoreThan(pageQueue, batchSize);
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
            throw new WebParserInterruptedException(page.getDomain());
        }
    }

    public Callable<Void> pageIndexingCallable() {
        String mainUrl = page.getMainUrl();
        String path = page.getPath();
        Site site = propertiesUtil.getSiteByUrlInConfig(mainUrl);

        return () -> {
            try {
                Page page = pageRepository.findBySiteAndPath(site, path);

                if (page == null) {
                    parsePage();
                    page = pageRepository.save(getPage());
                } else {
                    indexRepository.deleteAllByPage(page);
                    lemmaService.decrementLemmaFrequencyOrDelete(page);
                }
                lemmaService.saveLemmas(page);

                return null;
            } catch (IOException ex) {
                throw new PageAbsentException(page.getUrl());
            }
        };
    }

    private Document parsePage() throws IOException {
        String url = page.getUrl();

        if (!isValidUrl(url)) {
            throw new InvalidURLException(url);
        }

        Connection.Response response = urlConnect(url);
        Document doc = response.parse();

        page.setContent(doc.html());
        page.setCode(HttpStatus.valueOf(response.statusCode()));

        return doc;
    }

    private Page getPage() {
        Page page = new Page();
        page.setPath(this.page.getPath());
        page.setContent(this.page.getContent());
        page.setCode(this.page.getCode());
        page.setSite(site);

        return page;
    }

    private Collection<String> validLinks(Document doc) {
        String mainUrl = page.getMainUrl() + "/";
        String url = page.getUrl();
        String name = page.getName();

        Elements links = doc.select("a");
        return links.stream()
                .map(elem -> elem.absUrl("href"))
                .filter(link -> link.startsWith(mainUrl)
                        && !link.equals(mainUrl)
                        && !link.equals(url))
                .filter(propertiesUtil::checkTypeUrl)
                .filter(link -> {
                    synchronized (jedis) { // ensure links are checked and added consistently
                        return jedis.sadd(name, link) != 0;
                    }
                })
                .collect(Collectors.toSet());
    }

    private Collection<RecursiveWebParser> getRecursiveWebParsers(Collection<String> links) {
        return links.stream()
                .map(link -> getChildParser(new PageIntrospect(page.getName(), link)))
                .collect(Collectors.toSet());
    }

    private RecursiveWebParser getChildParser(PageIntrospect child) {
        try {
            RecursiveWebParser childParser = clone();
            childParser.setPage(child);
            childParser.setPageQueue(pageQueue);
            childParser.setPool(pool);
            childParser.setSite(site);
            return childParser;
        } catch (CloneNotSupportedException ex) {
            throw new RuntimeException(ex);
        }
    }

    private Connection.Response urlConnect(String url) throws IOException {
        return Jsoup.connect(url).execute();
    }

    private void insertPagesIfCountIsMoreThan(Queue<Page> pageQueue, int size) {
        if (pageQueue.size() > size) {
            pageRepository.saveAll(pageQueue);
            pageQueue.forEach(lemmaService::saveLemmas);
            pageQueue.clear();
        }
    }

    private void shutDownPoolIfExecuted() {
        String mainUrl = page.getMainUrl() + "/";
        String url = page.getUrl();

        if (mainUrl.equals(url)) {
            jedis.del(page.getName());
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
