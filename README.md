# 🎾 Court Notification

A Spring Boot web app that monitors tennis court availability on [kluby.org](https://kluby.org) and sends Telegram notifications when free slots are found.

## Features

- **Club search** — searchable dropdown populated from kluby.org at startup
- **Availability check** — scrapes the booking schedule for a selected club and date
- **Filtering** — filter by time range (from/to), specific court name, and minimum duration (1h / 1.5h / 2h)
- **Auto-polling** — check on a configurable interval (e.g. every 5 minutes)
- **Telegram notifications** — receive alerts in Telegram when slots open up; silent when nothing is found
- **Browser notifications** — desktop alert when free slots are detected
- **Live activity log** — real-time server-side log panel in the UI

## Tech Stack

- Java 17, Spring Boot 3.2.5
- Jsoup 1.17.2 (HTML scraping)
- Telegram Bot API (via HTTP)
- Vanilla JS + HTML/CSS (no frontend framework)

## Getting Started

### Prerequisites
- Java 17+
- Maven 3.8+

### Build & Run

```bash
mvn package -DskipTests
java -jar target/CourtNotification-1.0-SNAPSHOT.jar
```

Open [http://localhost:8080](http://localhost:8080) in your browser.

### Telegram Setup

1. Create a bot via [@BotFather](https://t.me/BotFather) and copy the token.
2. Send `/start` to your bot from your Telegram account.
3. In the app UI, enter the bot token and click **Save**.
4. Enter your Telegram username and click **Link**.
5. Notifications will be sent automatically when slots are found during polling.

## How It Works

The app scrapes the booking table at:
```
https://kluby.org/{clubSlug}/rezerwacje?data_grafiku={date}
```

It parses the HTML table with full **rowspan-aware** logic — multi-hour bookings span multiple rows as `<td rowspan="N">`, and the parser correctly tracks column occupancy to avoid false positives.
