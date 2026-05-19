package org.example.court;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class CourtController {

    private final CourtService courtService;
    private final ClubService clubService;
    private final TelegramService telegramService;
    private final LogService logService;
    private final ConfigurableApplicationContext appContext;

    private final ConcurrentHashMap<String, Long> linkedUsers = new ConcurrentHashMap<>();

    public CourtController(CourtService courtService, ClubService clubService,
                           TelegramService telegramService, LogService logService,
                           ConfigurableApplicationContext appContext) {
        this.courtService = courtService;
        this.clubService = clubService;
        this.telegramService = telegramService;
        this.logService = logService;
        this.appContext = appContext;
    }

    @GetMapping("/clubs")
    public ResponseEntity<?> clubs() {
        return ResponseEntity.ok(clubService.fetchClubs());
    }

    @GetMapping("/check")
    public ResponseEntity<?> check(
            @RequestParam String club,
            @RequestParam String date,
            @RequestParam(required = false) String court,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer duration,
            @RequestParam(required = false) String telegramUser) {
        try {
            List<AvailableSlot> slots = courtService.checkAvailability(club, date, court, from, to, duration);

            String durationLabel = duration != null ? " [" + duration + "min]" : "";
            if (slots.isEmpty()) {
                logService.log("Check " + club + " on " + date + durationLabel + " → no slots");
            } else {
                logService.log("Check " + club + " on " + date + durationLabel + " → " + slots.size() + " slot(s) found");
            }

            if (telegramUser != null && !telegramUser.isBlank() && !slots.isEmpty()) {
                String key = normalize(telegramUser);
                Long chatId = linkedUsers.get(key);
                if (chatId != null) {
                    telegramService.notifySlots(chatId, club, date, slots);
                } else {
                    logService.log("Telegram user @" + key + " not linked — skipping notification");
                }
            }

            return ResponseEntity.ok(slots);
        } catch (Exception e) {
            logService.log("ERROR checking " + club + ": " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/telegram/configure")
    public ResponseEntity<?> configure(@RequestParam String token) {
        telegramService.setToken(token);
        logService.log("Telegram bot token updated");
        return ResponseEntity.ok(Map.of("configured", true));
    }

    @PostMapping("/telegram/link")
    public ResponseEntity<?> link(@RequestParam String username) {
        if (!telegramService.isConfigured()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Bot token not set. Enter it in the Telegram section."));
        }
        logService.log("Linking Telegram user @" + normalize(username) + "…");
        try {
            Long chatId = telegramService.findChatId(username);
            if (chatId == null) {
                logService.log("Link failed — no /start message from @" + normalize(username));
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "No message found from @" + normalize(username) + ". Please send /start to the bot first."
                ));
            }
            linkedUsers.put(normalize(username), chatId);
            telegramService.sendMessage(chatId, "✅ Linked! You'll receive court availability notifications here.");
            logService.log("Linked @" + normalize(username) + " → chat ID " + chatId);
            return ResponseEntity.ok(Map.of("chatId", chatId));
        } catch (Exception e) {
            logService.log("ERROR linking @" + normalize(username) + ": " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/telegram/status")
    public ResponseEntity<?> status(@RequestParam String username) {
        Long chatId = linkedUsers.get(normalize(username));
        return ResponseEntity.ok(Map.of("linked", chatId != null));
    }

    @GetMapping(value = "/logs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> logs() {
        return ResponseEntity.ok(logService.recent());
    }

    @PostMapping("/shutdown")
    public ResponseEntity<?> shutdown() {
        logService.log("Shutdown requested from UI — stopping server…");
        new Thread(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            appContext.close();
        }).start();
        return ResponseEntity.ok(Map.of("message", "Shutting down…"));
    }

    private String normalize(String username) {
        return username.startsWith("@") ? username.substring(1).toLowerCase() : username.toLowerCase();
    }
}
