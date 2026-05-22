package org.example.court;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TelegramService {

    @Value("${telegram.bot.token:}")
    private String configToken;

    @Value("${telegram.chat.id:}")
    private String configChatId;

    private volatile String runtimeToken;

    private final ConcurrentHashMap<String, Long> linkedUsers = new ConcurrentHashMap<>();

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final LogService logService;

    public TelegramService(LogService logService) {
        this.logService = logService;
    }

    public void setToken(String token) {
        this.runtimeToken = token != null ? token.trim() : null;
    }

    private String token() {
        return (runtimeToken != null && !runtimeToken.isBlank()) ? runtimeToken : configToken;
    }

    public boolean isConfigured() {
        String t = token();
        return t != null && !t.isBlank();
    }

    /** Returns the chat ID from TELEGRAM_CHAT_ID env var, or null if not set. */
    public Long getConfiguredChatId() {
        if (configChatId == null || configChatId.isBlank()) return null;
        try { return Long.parseLong(configChatId.trim()); } catch (NumberFormatException e) { return null; }
    }

    public void linkUser(String username, Long chatId) {
        linkedUsers.put(normalize(username), chatId);
    }

    public Long getChatId(String username) {
        return linkedUsers.get(normalize(username));
    }

    public boolean isLinked(String username) {
        return linkedUsers.containsKey(normalize(username));
    }

    private String normalize(String u) {
        return u.startsWith("@") ? u.substring(1).toLowerCase() : u.toLowerCase();
    }

    /** Scan recent bot updates to find the chat ID for the given username (without @). */
    public Long findChatId(String username) throws Exception {
        String url = "https://api.telegram.org/bot" + token() + "/getUpdates?limit=100";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

        JsonNode root = mapper.readTree(res.body());
        if (!root.path("ok").asBoolean()) {
            throw new RuntimeException("Telegram API error: " + root.path("description").asText());
        }

        String target = username.startsWith("@") ? username.substring(1).toLowerCase() : username.toLowerCase();

        for (JsonNode update : root.path("result")) {
            JsonNode from = update.path("message").path("from");
            if (!from.isMissingNode()) {
                String uname = from.path("username").asText("").toLowerCase();
                if (uname.equals(target)) {
                    return update.path("message").path("chat").path("id").asLong();
                }
            }
        }
        return null;
    }

    public void sendMessage(long chatId, String text) throws Exception {
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
        String url = "https://api.telegram.org/bot" + token()
                + "/sendMessage?chat_id=" + chatId
                + "&text=" + encoded
                + "&parse_mode=HTML";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(res.body());
        if (!root.path("ok").asBoolean()) {
            throw new RuntimeException("Telegram send error: " + root.path("description").asText());
        }
    }

    public void notifySlots(long chatId, String club, String date, List<AvailableSlot> slots) throws Exception {
        if (slots.isEmpty()) {
            sendMessage(chatId, "No available slots at <b>" + club + "</b> on " + date);
            logService.log("Telegram → no slots at " + club + " on " + date);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🎾 <b>").append(slots.size()).append(" slot(s) available</b>\n");
        sb.append("<b>Club:</b> ").append(club).append("  <b>Date:</b> ").append(date).append("\n\n");
        for (AvailableSlot s : slots) {
            sb.append("• ").append(s.court()).append(" @ ").append(s.time())
              .append(" — <a href=\"").append(s.bookingUrl()).append("\">Book</a>\n");
        }
        sendMessage(chatId, sb.toString());
        logService.log("Telegram → sent " + slots.size() + " slot(s) to chat " + chatId);
    }
}
