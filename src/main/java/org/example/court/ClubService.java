package org.example.court;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClubService {

    private static final String CLUBS_URL = "https://kluby.org/tenis/kluby/warszawa";

    public List<Club> fetchClubs() throws IOException {
        Document doc = Jsoup.connect(CLUBS_URL)
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .timeout(15_000)
                .get();

        Elements links = doc.select("a[href*='?popularne_kluby=']");
        Map<String, Club> seen = new LinkedHashMap<>();

        for (Element link : links) {
            String href = link.attr("href");
            String slug = href.contains("?") ? href.substring(1, href.indexOf("?")) : href.substring(1);
            if (slug.isBlank() || seen.containsKey(slug)) continue;

            Element nameEl = link.selectFirst("h4, h3, h2, .name, strong");
            String name = nameEl != null ? nameEl.text().trim() : null;
            if (name == null || name.isBlank()) name = link.ownText().trim();
            if (name.isBlank()) name = slug;

            Element p = link.selectFirst("p");
            String location = p != null ? p.text().trim() : "";

            seen.put(slug, new Club(name, slug, location));
        }

        return new ArrayList<>(seen.values());
    }
}
