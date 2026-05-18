package org.example.court;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class CourtService {

    private static final String BASE_URL = "https://kluby.org/%s/rezerwacje?data_grafiku=%s";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public List<AvailableSlot> checkAvailability(
            String clubSlug, String date, String courtFilter,
            String fromTimeStr, String toTimeStr, Integer durationMinutes) throws IOException {

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

        // Collect all raw 30-min available slots
        List<AvailableSlot> raw = new ArrayList<>();

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
                    raw.add(new AvailableSlot(courtName, startTimeStr, "https://kluby.org" + link.attr("href")));
                }
            }
        }

        if (durationMinutes == null || durationMinutes <= 0) return raw;

        return filterByDuration(raw, durationMinutes);
    }

    /**
     * From raw 30-min slots, find all consecutive blocks covering the requested duration.
     * Returns one slot per valid block, with time shown as "HH:mm – HH:mm".
     */
    private List<AvailableSlot> filterByDuration(List<AvailableSlot> slots, int durationMinutes) {
        int slotsNeeded = (int) Math.ceil(durationMinutes / 30.0);

        // Group by court, preserving insertion order (time-sorted from the page)
        Map<String, List<AvailableSlot>> byCourt = new LinkedHashMap<>();
        for (AvailableSlot s : slots) {
            byCourt.computeIfAbsent(s.court(), k -> new ArrayList<>()).add(s);
        }

        List<AvailableSlot> result = new ArrayList<>();

        for (List<AvailableSlot> courtSlots : byCourt.values()) {
            courtSlots.sort(Comparator.comparing(AvailableSlot::time));

            for (int i = 0; i <= courtSlots.size() - slotsNeeded; i++) {
                boolean consecutive = true;
                for (int j = 1; j < slotsNeeded; j++) {
                    LocalTime t1 = LocalTime.parse(courtSlots.get(i + j - 1).time(), TIME_FMT);
                    LocalTime t2 = LocalTime.parse(courtSlots.get(i + j).time(), TIME_FMT);
                    if (!t2.equals(t1.plusMinutes(30))) {
                        consecutive = false;
                        break;
                    }
                }
                if (consecutive) {
                    String startTime = courtSlots.get(i).time();
                    String endTime = LocalTime.parse(courtSlots.get(i + slotsNeeded - 1).time(), TIME_FMT)
                            .plusMinutes(30).format(TIME_FMT);
                    result.add(new AvailableSlot(
                            courtSlots.get(i).court(),
                            startTime + " – " + endTime,
                            courtSlots.get(i).bookingUrl()
                    ));
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
        String[] words = s.split("\\s+");
        if (words.length % 2 == 0) {
            int half = words.length / 2;
            String firstHalf = String.join(" ", Arrays.copyOf(words, half));
            String secondHalf = String.join(" ", Arrays.copyOfRange(words, half, words.length));
            if (firstHalf.equals(secondHalf)) return firstHalf;
        }
        return s;
    }
}
