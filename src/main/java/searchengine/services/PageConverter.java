package searchengine.services;

import searchengine.dto.recursive.PageRecursive;
import searchengine.model.Page;

public class PageConverter {
    public static Page convertPageRecursiveToPage(PageRecursive pageRecursive) {
        Page page = new Page();
        page.setPath(pageRecursive.getPath());
        page.setContent(pageRecursive.getContent());
        page.setCode(pageRecursive.getCode());

        return page;
    }
}
