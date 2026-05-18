package org.example.court;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class CourtService {

    private static final String BASE_URL = "https://kluby.org/%s/rezerwacje?data_grafiku=%s";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public List<AvailableSlot> checkAvailability(
            String clubSlug, String date, String courtFilter,
            String fromTimeStr, String toTimeStr) throws IOException {

        LocalTime fromTime = fromTimeStr != null && !fromTimeStr.isBlank() ? LocalTime.parse(fromTimeStr, TIME_FMT) : null;
        LocalTime toTime = toTimeStr != null && !toTimeStr.isBlank() ? LocalTime.parse(toTimeStr, TIME_FMT) : null;

        String url = String.format(BASE_URL, clubSlug, date);

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .timeout(15_000)
                .get();

        Elements headers = doc.select("table th");
        List<String> courtNames = new ArrayList<>();
        for (Element th : headers) {
            String name = dedup(th.text().trim());
            if (!name.isEmpty()) courtNames.add(name);
        }

        List<AvailableSlot> result = new ArrayList<>();

        Elements rows = doc.select("table tr");
        for (Element row : rows) {
            Elements cells = row.select("td");
            if (cells.isEmpty()) continue;

            String timeLabel = cells.get(0).text().trim();
            if (timeLabel.isEmpty()) continue;

            String startTimeStr = timeLabel.contains(" - ") ? timeLabel.split(" - ")[0].trim() : timeLabel;
            LocalTime slotTime;
            try {
                slotTime = LocalTime.parse(startTimeStr, TIME_FMT);
            } catch (Exception e) {
                continue;
            }

            if (fromTime != null && slotTime.isBefore(fromTime)) continue;
            if (toTime != null && !slotTime.isBefore(toTime)) continue;

            for (int i = 1; i < cells.size(); i++) {
                String courtName = i - 1 < courtNames.size() ? courtNames.get(i - 1) : "Court " + i;

                if (courtFilter != null && !courtFilter.isBlank()
                        && !courtName.toLowerCase().contains(courtFilter.toLowerCase())) continue;

                Element link = cells.get(i).selectFirst("a[href*=rezerwuj]");
                if (link != null) {
                    result.add(new AvailableSlot(courtName, startTimeStr, "https://kluby.org" + link.attr("href")));
                }
            }
        }

        return result;
    }

    /** If the string is an exact doubled repetition ("Kort 4 Kort 4"), return the first half. */
    private static String dedup(String s) {
        if (s.length() % 2 == 0) {
            int half = s.length() / 2;
            String first = s.substring(0, half);
            String second = s.substring(half).trim();
            if (first.trim().equals(second)) return first.trim();
        }
        // Also handle "A B A B" patterns by taking words up to the midpoint
        String[] words = s.split("\\s+");
        if (words.length % 2 == 0) {
            int half = words.length / 2;
            String firstHalf = String.join(" ", java.util.Arrays.copyOf(words, half));
            String secondHalf = String.join(" ", java.util.Arrays.copyOfRange(words, half, words.length));
            if (firstHalf.equals(secondHalf)) return firstHalf;
        }
        return s;
    }
}
