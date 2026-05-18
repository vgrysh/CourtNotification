package org.example.court;

import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

@Service
public class LogService {

    private static final int MAX = 200;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Deque<String> entries = new ArrayDeque<>();

    public synchronized void log(String message) {
        String entry = "[" + LocalTime.now().format(FMT) + "] " + message;
        entries.addLast(entry);
        if (entries.size() > MAX) entries.removeFirst();
        System.out.println(entry);
    }

    public synchronized List<String> recent() {
        return List.copyOf(entries);
    }
}
