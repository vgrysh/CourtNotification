package org.example.court;

public record PollingConfig(
        String club,
        String date,
        String court,
        String from,
        String to,
        Integer duration,
        String telegramUser,
        int intervalMinutes
) {}
