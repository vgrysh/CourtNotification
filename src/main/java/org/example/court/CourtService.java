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

    private static final String BASE_URL   = "https://kluby.org/%s/rezerwacje?data_grafiku=%s";
    private static final DateTimeFormatter TIME_FMT   = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter TIME_PARSE = DateTimeFormatter.ofPattern("H:mm");

    private final LogService logService;

    public CourtService(LogService logService) {
        this.logService = logService;
    }

    public List<AvailableSlot> checkAvailability(
            String clubSlug, String date, String courtFilter,
            String fromTimeStr, String toTimeStr, Integer durationMinutes) throws IOException {

        LocalTime fromTime = fromTimeStr != null && !fromTimeStr.isBlank() ? LocalTime.parse(fromTimeStr, TIME_FMT) : null;
        LocalTime toTime   = toTimeStr   != null && !toTimeStr.isBlank()   ? LocalTime.parse(toTimeStr,   TIME_FMT) : null;

        String url = String.format(BASE_URL, clubSlug, date);
        logService.log("Fetching: " + url);

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .timeout(15_000)
                .get();

        // ── Parse court names from header ────────────────────────────
        Elements headers = doc.select("table th");
        List<String> courtNames = new ArrayList<>();
        for (Element th : headers) {
            Element nameEl = th.selectFirst("div.text-nowrap");
            String name = nameEl != null ? nameEl.text().trim() : dedup(th.text().trim());
            if (!name.isEmpty()) courtNames.add(name);
        }
        logService.log("Courts found: " + courtNames);

        int totalCols = courtNames.size() + 1; // +1 for the time column

        // ── Rowspan-aware grid parsing ────────────────────────────────
        // carryRows[col] = how many more rows this column is occupied by a spanning cell
        int[] carryRows = new int[totalCols];

        List<AvailableSlot> raw = new ArrayList<>();
        int rowsScanned = 0, rowsSkippedTime = 0, rowsSkippedParse = 0;
        int cellsChecked = 0, cellsAvailable = 0, cellsBooked = 0, cellsFiltered = 0;

        Elements rows = doc.select("table tr");
        for (Element row : rows) {
            Elements rawCells = row.select("td");
            if (rawCells.isEmpty()) continue;

            // Build the effective cell list for this row, accounting for rowspans
            List<Element> effective = new ArrayList<>(Collections.nCopies(totalCols, null));
            int rawIdx = 0;
            for (int col = 0; col < totalCols; col++) {
                if (carryRows[col] > 0) {
                    carryRows[col]--;
                    // effective[col] stays null — occupied by a span from a previous row
                } else if (rawIdx < rawCells.size()) {
                    Element cell = rawCells.get(rawIdx++);
                    int rowspan = parseInt(cell.attr("rowspan"), 1);
                    int colspan = parseInt(cell.attr("colspan"), 1);
                    effective.set(col, cell);
                    // Mark spanned columns
                    for (int c = col; c < col + colspan && c < totalCols; c++) {
                        if (rowspan > 1) carryRows[c] = rowspan - 1;
                    }
                    col += colspan - 1; // skip extra columns consumed by colspan
                }
            }

            // effective[0] = time cell
            Element timeCell = effective.get(0);
            if (timeCell == null) continue;

            String timeLabel = timeCell.text().trim();
            if (timeLabel.isEmpty()) continue;

            String startTimeStr = timeLabel.contains(" - ") ? timeLabel.split(" - ")[0].trim() : timeLabel;
            LocalTime slotTime;
            try {
                slotTime = LocalTime.parse(startTimeStr, TIME_PARSE);
            } catch (Exception e) {
                rowsSkippedParse++;
                logService.log("  Skipping unparseable time: '" + timeLabel + "'");
                continue;
            }

            rowsScanned++;

            if (fromTime != null && slotTime.isBefore(fromTime)) { rowsSkippedTime++; continue; }
            if (toTime   != null && !slotTime.isBefore(toTime))  { rowsSkippedTime++; continue; }

            // effective[1..n] = court cells
            for (int col = 1; col < totalCols; col++) {
                String courtName = courtNames.get(col - 1);
                cellsChecked++;

                if (courtFilter != null && !courtFilter.isBlank()
                        && !courtName.toLowerCase().contains(courtFilter.toLowerCase())) {
                    cellsFiltered++;
                    continue;
                }

                Element cell = effective.get(col);
                if (cell == null) {
                    // Occupied by a rowspan from a previous row → booked
                    cellsBooked++;
                    logService.log("  BOOKED(span): " + courtName + " @ " + startTimeStr);
                    continue;
                }

                Element link = cell.selectFirst("a[href*=rezerwuj]");
                if (link != null) {
                    cellsAvailable++;
                    String href = "https://kluby.org" + link.attr("href");
                    logService.log("  AVAILABLE: " + courtName + " @ " + startTimeStr + " → " + href);
                    raw.add(new AvailableSlot(courtName, startTimeStr, href));
                } else {
                    cellsBooked++;
                    String cellText = cell.text().trim();
                    logService.log("  BOOKED:    " + courtName + " @ " + startTimeStr
                            + (cellText.isEmpty() ? "" : " [" + cellText.substring(0, Math.min(cellText.length(), 40)) + "]"));
                }
            }
        }

        logService.log(String.format(
                "Scan: %d rows scanned, %d out-of-range, %d parse errors | %d cells: %d available, %d booked, %d filtered",
                rowsScanned, rowsSkippedTime, rowsSkippedParse, cellsChecked, cellsAvailable, cellsBooked, cellsFiltered));

        if (durationMinutes == null || durationMinutes <= 0) return raw;

        List<AvailableSlot> filtered = filterByDuration(raw, durationMinutes);
        logService.log("After duration filter (" + durationMinutes + "min): " + filtered.size() + " block(s)");
        return filtered;
    }

    private List<AvailableSlot> filterByDuration(List<AvailableSlot> slots, int durationMinutes) {
        int slotsNeeded = (int) Math.ceil(durationMinutes / 30.0);

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
                    LocalTime t1 = LocalTime.parse(courtSlots.get(i + j - 1).time(), TIME_PARSE);
                    LocalTime t2 = LocalTime.parse(courtSlots.get(i + j).time(), TIME_PARSE);
                    if (!t2.equals(t1.plusMinutes(30))) { consecutive = false; break; }
                }
                if (consecutive) {
                    String start = courtSlots.get(i).time();
                    String end   = LocalTime.parse(courtSlots.get(i + slotsNeeded - 1).time(), TIME_PARSE)
                            .plusMinutes(30).format(TIME_FMT);
                    result.add(new AvailableSlot(courtSlots.get(i).court(), start + " – " + end, courtSlots.get(i).bookingUrl()));
                }
            }
        }
        return result;
    }

    private static int parseInt(String s, int def) {
        try { return s == null || s.isBlank() ? def : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static String dedup(String s) {
        if (s.length() % 2 == 0) {
            int half = s.length() / 2;
            String first = s.substring(0, half), second = s.substring(half).trim();
            if (first.trim().equals(second)) return first.trim();
        }
        String[] words = s.split("\\s+");
        if (words.length % 2 == 0) {
            int half = words.length / 2;
            String a = String.join(" ", Arrays.copyOf(words, half));
            String b = String.join(" ", Arrays.copyOfRange(words, half, words.length));
            if (a.equals(b)) return a;
        }
        return s;
    }
}
