package org.example.court;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CourtController {

    private final CourtService courtService;
    private final ClubService clubService;
    private final TelegramService telegramService;
    private final LogService logService;
    private final PollingService pollingService;
    private final ConfigurableApplicationContext appContext;

    public CourtController(CourtService courtService, ClubService clubService,
                           TelegramService telegramService, LogService logService,
                           PollingService pollingService, ConfigurableApplicationContext appContext) {
        this.courtService = courtService;
        this.clubService = clubService;
        this.telegramService = telegramService;
        this.logService = logService;
        this.pollingService = pollingService;
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
                Long chatId = telegramService.getChatId(telegramUser);
                if (chatId != null) {
                    telegramService.notifySlots(chatId, club, date, slots);
                } else {
                    logService.log("Telegram user @" + normalize(telegramUser) + " not linked — skipping notification");
                }
            }

            return ResponseEntity.ok(slots);
        } catch (Exception e) {
            logService.log("ERROR checking " + club + ": " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Called by Google Cloud Scheduler on a cron schedule.
     * Checks availability and sends a Telegram notification if slots are found.
     * Uses TELEGRAM_CHAT_ID env var as the notification target.
     */
    @PostMapping("/check-and-notify")
    public ResponseEntity<?> checkAndNotify(
            @RequestParam String club,
            @RequestParam String date,
            @RequestParam(required = false) String court,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer duration) {
        try {
            List<AvailableSlot> slots = courtService.checkAvailability(club, date, court, from, to, duration);
            logService.log("check-and-notify: " + slots.size() + " slot(s) at " + club + " on " + date);

            if (!slots.isEmpty()) {
                Long chatId = telegramService.getConfiguredChatId();
                if (chatId != null) {
                    telegramService.notifySlots(chatId, club, date, slots);
                } else {
                    logService.log("check-and-notify: TELEGRAM_CHAT_ID not set — skipping notification");
                }
            }
            return ResponseEntity.ok(Map.of("slots", slots.size()));
        } catch (Exception e) {
            logService.log("ERROR in check-and-notify: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Polling ──────────────────────────────────────────────────────────────

    @PostMapping("/poll/start")
    public ResponseEntity<?> pollStart(
            @RequestParam String club,
            @RequestParam String date,
            @RequestParam(required = false) String court,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer duration,
            @RequestParam(required = false) String telegramUser,
            @RequestParam(defaultValue = "5") int intervalMinutes) {
        PollingConfig cfg = new PollingConfig(club, date, court, from, to, duration, telegramUser, intervalMinutes);
        String id = pollingService.start(cfg);
        return ResponseEntity.ok(Map.of("id", id, "started", true));
    }

    @PostMapping("/poll/stop")
    public ResponseEntity<?> pollStop(@RequestParam(required = false) String id) {
        if (id != null && !id.isBlank()) {
            boolean stopped = pollingService.stop(id);
            return ResponseEntity.ok(Map.of("stopped", stopped));
        }
        int count = pollingService.stopAll();
        return ResponseEntity.ok(Map.of("stopped", count));
    }

    @GetMapping("/poll/status")
    public ResponseEntity<?> pollStatus() {
        List<Map<String, Object>> result = pollingService.getAll().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("running", t.isRunning());
            m.put("lastCheck", t.getLastCheckTime());
            m.put("lastResult", t.getLastCheckResult());
            PollingConfig cfg = t.getConfig();
            m.put("club", cfg.club());
            m.put("date", cfg.date());
            m.put("from", cfg.from());
            m.put("to", cfg.to());
            m.put("court", cfg.court());
            m.put("intervalMinutes", cfg.intervalMinutes());
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    // ── Telegram ──────────────────────────────────────────────────────────────

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
            telegramService.linkUser(username, chatId);
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
        return ResponseEntity.ok(Map.of("linked", telegramService.isLinked(username)));
    }

    // ── Logs & shutdown ───────────────────────────────────────────────────────

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
