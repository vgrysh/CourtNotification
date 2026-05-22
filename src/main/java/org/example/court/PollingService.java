package org.example.court;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class PollingService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final CourtService courtService;
    private final TelegramService telegramService;
    private final LogService logService;
    private final TaskScheduler taskScheduler;

    private final ConcurrentHashMap<String, PollingTask> tasks = new ConcurrentHashMap<>();

    public PollingService(CourtService courtService, TelegramService telegramService,
                          LogService logService, TaskScheduler taskScheduler) {
        this.courtService = courtService;
        this.telegramService = telegramService;
        this.logService = logService;
        this.taskScheduler = taskScheduler;
    }

    /** Starts a new polling task and returns its ID. */
    public String start(PollingConfig cfg) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Duration period = Duration.ofMinutes(cfg.intervalMinutes());
        ScheduledFuture<?> future = taskScheduler.scheduleAtFixedRate(
                () -> poll(id), Instant.now(), period);
        tasks.put(id, new PollingTask(id, cfg, future));
        logService.log("Poll [" + id + "] started: " + cfg.club() + " every " + cfg.intervalMinutes() + " min");
        return id;
    }

    /** Stops the task with the given ID. Returns false if not found. */
    public boolean stop(String id) {
        PollingTask task = tasks.remove(id);
        if (task != null) {
            task.getFuture().cancel(false);
            logService.log("Poll [" + id + "] stopped");
            return true;
        }
        return false;
    }

    /** Stops all running tasks. */
    public int stopAll() {
        int count = tasks.size();
        tasks.forEach((id, task) -> {
            task.getFuture().cancel(false);
            logService.log("Poll [" + id + "] stopped");
        });
        tasks.clear();
        return count;
    }

    public Collection<PollingTask> getAll() {
        return tasks.values();
    }

    public boolean hasRunning() {
        return !tasks.isEmpty();
    }

    private void poll(String id) {
        PollingTask task = tasks.get(id);
        if (task == null) return;
        PollingConfig config = task.getConfig();
        try {
            List<AvailableSlot> slots = courtService.checkAvailability(
                    config.club(), config.date(), config.court(),
                    config.from(), config.to(), config.duration());

            String now = LocalTime.now().format(TIME_FMT);
            task.setLastCheckTime(now);

            if (slots.isEmpty()) {
                task.setLastCheckResult("no slots");
                logService.log("Poll [" + id + "]: no slots at " + config.club() + " on " + config.date());
            } else {
                task.setLastCheckResult(slots.size() + " slot(s) found");
                logService.log("Poll [" + id + "]: " + slots.size() + " slot(s) at " + config.club() + " on " + config.date());

                String user = config.telegramUser();
                if (user != null && !user.isBlank()) {
                    Long chatId = telegramService.getChatId(user);
                    if (chatId != null) {
                        telegramService.notifySlots(chatId, config.club(), config.date(), slots);
                    } else {
                        logService.log("Poll [" + id + "]: Telegram user @" + user + " not linked — skipping");
                    }
                }
            }
        } catch (Exception e) {
            task.setLastCheckTime(LocalTime.now().format(TIME_FMT));
            task.setLastCheckResult("error");
            logService.log("Poll [" + id + "] error: " + e.getMessage());
        }
    }
}
