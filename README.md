# 🎾 Court Notification

A Spring Boot web app that monitors tennis court availability on [kluby.org](https://kluby.org) and sends Telegram notifications when free slots are found.

## Features

- **Club search** — searchable dropdown populated from kluby.org at startup
- **Availability check** — scrapes the booking schedule for a selected club and date
- **Filtering** — filter by time range (from/to), specific court name, and minimum duration (1h / 1.5h / 2h)
- **Auto-polling** — server-side polling that runs independently of the browser
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

## Deploying to Google Cloud Run (free tier)

Cloud Run scales to zero when idle — no requests, no cost. Periodic checks are handled by **Cloud Scheduler** instead of a long-running thread.

### Architecture

```
Cloud Scheduler (cron)  →  POST /api/check-and-notify  →  Cloud Run instance
                                                               ↓ (if slots found)
                                                         Telegram notification
```

### Prerequisites

```bash
gcloud auth login
gcloud config set project YOUR_PROJECT_ID
gcloud services enable run.googleapis.com cloudscheduler.googleapis.com artifactregistry.googleapis.com cloudbuild.googleapis.com
```

### 1. Get your Telegram chat ID

Send `/start` to your bot, then open in browser (replace `BOT_TOKEN`):
```
https://api.telegram.org/botBOT_TOKEN/getUpdates
```
Find `"chat":{"id": 123456789}` — that number is your `TELEGRAM_CHAT_ID`.

### 2. Build & push the container

```bash
gcloud builds submit --tag gcr.io/YOUR_PROJECT_ID/court-notification
```

### 3. Deploy to Cloud Run

```bash
gcloud run deploy court-notification \
  --image gcr.io/YOUR_PROJECT_ID/court-notification \
  --platform managed \
  --region europe-central2 \
  --allow-unauthenticated \
  --memory 512Mi \
  --min-instances 0 \
  --max-instances 1 \
  --set-env-vars "TELEGRAM_BOT_TOKEN=YOUR_BOT_TOKEN,TELEGRAM_CHAT_ID=YOUR_CHAT_ID"
```

Note the deployed URL, e.g. `https://court-notification-xxxx-ew.a.run.app`.

### 4. Create a Cloud Scheduler job

Replace the URL, club slug, and times to match your preferences:

```bash
gcloud scheduler jobs create http court-check \
  --schedule "*/5 8-20 * * *" \
  --uri "https://YOUR_CLOUD_RUN_URL/api/check-and-notify?club=timing&date=$(date +%Y-%m-%d)&from=08:00&to=20:00&duration=60" \
  --http-method POST \
  --location europe-central2 \
  --time-zone "Europe/Warsaw"
```

> **Tip:** `*/5 8-20 * * *` runs every 5 minutes between 08:00 and 20:00. Adjust to your playing hours to avoid unnecessary cold starts.

### 5. Test it manually

```bash
curl -X POST "https://YOUR_CLOUD_RUN_URL/api/check-and-notify?club=timing&date=2026-05-23&from=08:00&to=20:00&duration=60"
```

### Free tier estimate

| Service | Usage | Free allowance | Cost |
|---|---|---|---|
| Cloud Run | ~1,440 req/day × ~3s × 512MB | 360K GB-s/month | **$0** |
| Cloud Scheduler | 1 job | 3 free jobs | **$0** |
| Artifact Registry | ~200 MB image | 0.5 GB free | **$0** |
| **Total** | | | **$0/month** |

### Environment variables reference

| Variable | Description |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Bot token from @BotFather |
| `TELEGRAM_CHAT_ID` | Your Telegram chat ID (numeric) |
| `PORT` | Injected automatically by Cloud Run (default 8080) |

---

## How It Works

The app scrapes the booking table at:
```
https://kluby.org/{clubSlug}/rezerwacje?data_grafiku={date}
```

It parses the HTML table with full **rowspan-aware** logic — multi-hour bookings span multiple rows as `<td rowspan="N">`, and the parser correctly tracks column occupancy to avoid false positives.
