# 🎾 Court Notification

A Spring Boot web app that monitors tennis court availability on [kluby.org](https://kluby.org) and sends Telegram notifications when free slots are found.

## Features

- **Club search** — searchable dropdown populated from kluby.org at startup
- **Availability check** — scrapes the booking schedule for a selected club and date
- **Filtering** — filter by time range (from/to), specific court name, and minimum duration (1h / 1.5h / 2h)
- **Parallel polling** — run multiple independent server-side polls simultaneously (different clubs, dates, or time ranges); each poll has its own ID and can be stopped individually
- **Telegram notifications** — receive alerts in Telegram only when slots are found; silent when nothing is available
- **Browser notifications** — desktop alert when free slots are detected
- **Live activity log** — real-time server-side log panel in the UI
- **Stop server button** — shut down the server from the browser UI

## Tech Stack

- Java 17, Spring Boot 3.2.5
- Jsoup 1.17.2 (HTML scraping)
- Telegram Bot API (via HTTP)
- Vanilla JS + HTML/CSS (no frontend framework)

---

## Running locally

### Prerequisites
- Java 17+, Maven 3.8+

### macOS Launcher (recommended)

Double-click **`CourtNotification.app`** in the project folder — builds the JAR if needed, starts the server, opens the browser.

### Manual

```bash
mvn package -DskipTests
java -jar target/CourtNotification-1.0-SNAPSHOT.jar
```

Open [http://localhost:8080](http://localhost:8080).

### Telegram Setup (local)

1. Create a bot via [@BotFather](https://t.me/BotFather) and copy the token.
2. Send `/start` to your bot from your Telegram account.
3. In the app UI, enter the bot token → **Save**, then your username → **Link**.

---

## How It Works

The app scrapes the booking table at:
```
https://kluby.org/{clubSlug}/rezerwacje?data_grafiku={date}
```

It parses the HTML table with full **rowspan-aware** logic — multi-hour bookings span multiple rows as `<td rowspan="N">`, and the parser correctly tracks column occupancy to avoid false positives.

### Parallel polling

Each time you click **Add Poll**, a new independent background task starts on the server (backed by Spring `TaskScheduler`). Tasks run concurrently and survive page refreshes. The UI syncs with the server every 4 seconds and shows all active polls with their last check time and result. Individual polls can be stopped via their **Stop** button, or all at once with **Stop all**.

### API reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/clubs` | List all clubs |
| `GET` | `/api/check` | One-off availability check |
| `POST` | `/api/poll/start` | Start a new polling task; returns `{id}` |
| `POST` | `/api/poll/stop?id=<id>` | Stop a specific poll |
| `POST` | `/api/poll/stop` | Stop all polls |
| `GET` | `/api/poll/status` | List all active polls with last result |
| `POST` | `/api/telegram/configure` | Set bot token |
| `POST` | `/api/telegram/link` | Link a Telegram username |
| `POST` | `/api/shutdown` | Gracefully stop the server |
