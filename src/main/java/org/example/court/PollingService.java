package org.example.court;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

@Service
public class PollingService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final CourtService courtService;
    private final TelegramService telegramService;
    private final LogService logService;
    private final TaskScheduler taskScheduler;

    private volatile ScheduledFuture<?> task;
    private volatile PollingConfig config;
    private volatile String lastCheckTime;
    private volatile String lastCheckResult;

    public PollingService(CourtService courtService, TelegramService telegramService,
                          LogService logService, TaskScheduler taskScheduler) {
        this.courtService = courtService;
        this.telegramService = telegramService;
        this.logService = logService;
        this.taskScheduler = taskScheduler;
    }

    public synchronized void start(PollingConfig cfg) {
        stopInternal(false);
        this.config = cfg;
        this.lastCheckTime = null;
        this.lastCheckResult = null;
        Duration period = Duration.ofMinutes(cfg.intervalMinutes());
        task = taskScheduler.scheduleAtFixedRate(this::poll, Instant.now(), period);
        logService.log("Server polling started: " + cfg.club() + " every " + cfg.intervalMinutes() + " min");
    }

    public synchronized void stop() {
        stopInternal(true);
    }

    private void stopInternal(boolean log) {
        if (task != null) {
            task.cancel(false);
            task = null;
            if (log) logService.log("Server polling stopped");
        }
    }

    public boolean isRunning() {
        return task != null && !task.isDone();
    }

    public PollingConfig getConfig() { return config; }
    public String getLastCheckTime() { return lastCheckTime; }
    public String getLastCheckResult() { return lastCheckResult; }

    private void poll() {
        if (config == null) return;
        try {
            List<AvailableSlot> slots = courtService.checkAvailability(
                    config.club(), config.date(), config.court(),
                    config.from(), config.to(), config.duration());

            lastCheckTime = LocalTime.now().format(TIME_FMT);

            if (slots.isEmpty()) {
                lastCheckResult = "no slots";
                logService.log("Poll: no slots at " + config.club() + " on " + config.date());
            } else {
                lastCheckResult = slots.size() + " slot(s) found";
                logService.log("Poll: " + slots.size() + " slot(s) found at " + config.club() + " on " + config.date());

                String user = config.telegramUser();
                if (user != null && !user.isBlank()) {
                    Long chatId = telegramService.getChatId(user);
                    if (chatId != null) {
                        telegramService.notifySlots(chatId, config.club(), config.date(), slots);
                    } else {
                        logService.log("Poll: Telegram user @" + user + " not linked — skipping notification");
                    }
                }
            }
        } catch (Exception e) {
            lastCheckTime = LocalTime.now().format(TIME_FMT);
            lastCheckResult = "error";
            logService.log("Poll error: " + e.getMessage());
        }
    }
}
