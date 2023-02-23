package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import searchengine.config.JsoupConnectionConfig;
import searchengine.dto.recursive.PageRecursive;
import searchengine.exceptions.InvalidURLException;
import searchengine.exceptions.WebParserInterruptedException;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.stream.Collectors;

@Component
@Scope("prototype")
@RequiredArgsConstructor
@Setter
class RecursiveWebParser extends RecursiveAction {
    private final JsoupConnectionConfig jsoupConnectionConfig;
    private final ApplicationContext applicationContext;
    private Collection<String> forbiddenTypesList;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private PageRecursive pageRecursive;
    private Queue<Page> pageQueue;
    private ForkJoinPool pool;
    private Jedis jedis;
    private Site site;

    @SneakyThrows(WebParserInterruptedException.class)
    @Override
    protected void compute() {
        try {
            String url = pageRecursive.getUrl();

            if (!isValidUrl(url)) {
                throw new InvalidURLException(url);
            }

            Connection.Response response = urlConnect(url);
            Document doc = response.parse();

            pageRecursive.setContent(doc.html());
            pageRecursive.setCode(HttpStatus.valueOf(response.statusCode()));

            Page page = PageConverter.convertPageRecursiveToPage(pageRecursive);
            page.setSite(site);
            pageQueue.add(page);

            insertPagesIfCountIsMoreThan(40);
            updateStatusTime();

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

    private Collection<String> validLinks(Document doc) {
        String mainUrl = pageRecursive.getMainUrl();
        String url = pageRecursive.getUrl();
        String name = pageRecursive.getName();

        Elements links = doc.select("a");
        return links.stream()
                .map(elem -> elem.absUrl("href"))
                .filter(link -> link.startsWith(mainUrl)
                        && !link.equals(mainUrl)
                        && !link.equals(url))
                .filter(this::checkTypeUrl)
                .filter(link -> {
                    synchronized (jedis) {
                        return jedis.sadd(name, link) != 0;
                    }
                })
                .collect(Collectors.toSet());
    }

    private boolean checkTypeUrl(String url) {
        return forbiddenTypesList == null || forbiddenTypesList.stream().noneMatch(url::contains);
    }

    private Collection<RecursiveWebParser> getRecursiveWebParsers(Collection<String> links) {
        return links.stream()
                .map(link -> getChildParser(new PageRecursive(pageRecursive.getName(), link)))
                .collect(Collectors.toSet());
    }

    private RecursiveWebParser getChildParser(PageRecursive child) {
        RecursiveWebParser childParser = applicationContext.getBean(RecursiveWebParser.class);
        childParser.setForbiddenTypesList(forbiddenTypesList);
        childParser.setPageRecursive(child);
        childParser.setPageQueue(pageQueue);
        childParser.setJedis(jedis);
        childParser.setPool(pool);
        childParser.setSite(site);

        return childParser;
    }

    private Connection.Response urlConnect(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(jsoupConnectionConfig.getUserAgent())
                .referrer(jsoupConnectionConfig.getReferrer())
                .execute();
    }

    private void insertPagesIfCountIsMoreThan(int size) {
        if (pageQueue.size() > size) {
            synchronized (pageQueue) {
                pageRepository.saveAll(pageQueue);
                pageQueue.clear();
            }
        }
    }

    private void shutDownPoolIfExecuted() {
        String mainUrl = pageRecursive.getMainUrl();
        String url = pageRecursive.getUrl();

        if (mainUrl.equals(url)) {
            jedis.del(pageRecursive.getName());
            pool.shutdownNow();
        }
    }

    private void updateStatusTime() {
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

    private boolean isValidUrl(String url) {
        return url.matches("^(https?)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]");
    }
}
