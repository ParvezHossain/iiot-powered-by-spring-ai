# Automatic anomaly alerts and Gmail delivery

With `ALERTS_ENABLED=true` (the default), a background worker runs once per second
and evaluates recently committed readings through the rolling detector. No REST,
agent, or MCP request is needed. A detected anomaly produces a WARN log such as:

```text
ANOMALY_ALERT readingId=84 machine=... metric=vibration_mm_s value=12.1 reason=HIGH_VIBRATION sampledAt=... zScore=...
```

Each reading produces at most one alert record in `telemetry.anomaly_alerts`,
keyed by reading ID. A temperature/vibration spike can therefore create two alerts.
The payload includes the reading, reason, and baseline statistics. The worker
checks an overlapping one-minute window beginning no earlier than application
readiness; the database key prevents duplicate records on subsequent polls.
Existing history is used for the statistical baseline, but startup does not send
a backlog of historical anomalies. Readings arriving over a minute late, or
created while the app is down, can fall outside this live monitoring window.

## See an alert quickly

The simulator normally injects a fault once a minute. For a quick local run,
shorten the sample interval while keeping enough warm-up samples:

```sh
SIMULATOR_MACHINE_COUNT=1 SIMULATOR_INTERVAL_MS=100 SIMULATOR_ANOMALY_EVERY_TICKS=12 \
  ./mvnw spring-boot:run
```

The first spike occurs roughly 1.2 seconds after simulation begins, with the
visible alert normally arriving within the next one-second poll. Log detection
does not depend on Gmail availability. `python3 scripts/demo.py` also verifies
the automatic hook and prints its result, with Gmail explicitly disabled.

## Send through Gmail

Gmail delivery is opt-in. Use a Gmail/Google Workspace account and an app password,
not the account's normal password. Google requires 2-Step Verification, and some
organization/account policies do not allow app passwords. See Google's
[app-password setup](https://support.google.com/accounts/answer/185833?hl=en) and
[SMTP settings](https://support.google.com/mail/answer/7104828?hl=en-uk).

Set these environment variables in your terminal or deployment secret settings:

```sh
export ALERTS_GMAIL_ENABLED=true
export GMAIL_USERNAME=your-account@gmail.com
export ALERT_EMAIL_TO=your-recipient@example.com
read -rs -p 'Gmail app password: ' GMAIL_APP_PASSWORD
export GMAIL_APP_PASSWORD
./mvnw spring-boot:run
```

The password is read without echoing it or putting it in shell history. Do not
commit it to the repository. With Compose, the same variables are forwarded to
the app; preserve existing port/model overrides when running
`docker compose up -d --build --wait app`. Missing credentials or invalid addresses
prevent startup when Gmail delivery is enabled.

The sender connects only to `smtp.gmail.com:587`, with SMTP authentication,
required STARTTLS, and server-certificate identity checking. Connection, read,
and write timeouts are five seconds each. From is the configured Gmail account;
To is the single configured recipient. The subject includes the anomaly reason
and machine UUID; the body includes the reading ID, timestamp, metric/value, and
baseline evidence. There is no claim that a statistical flag is a diagnosis.

The email worker uses a separate scheduler thread from detection. `PENDING`
deliveries persist in the database, retry after 30 seconds on failure, and become
`FAILED` after five attempts. A successful SMTP send becomes `SENT` and logs
`ANOMALY_EMAIL_SENT`. SMTP errors log only the reading ID/attempt, not credentials
or provider exception text. Log-only alerts are `NOT_REQUESTED` and are not emailed
retroactively if Gmail is enabled later.

Use one alert-worker app instance per database. This minimal implementation is
not a distributed delivery queue. A crash after SMTP accepts a message but before
the `SENT` update can produce a duplicate on retry; SMTP acceptance also does not
guarantee inbox delivery. Pending deliveries survive a PostgreSQL-backed restart,
but the default in-memory H2 database disappears on shutdown. Alert records are
retained until explicitly removed; there is no automatic retention policy.

Inspect delivery status without revealing account settings:

```sql
SELECT reading_id, detected_at, email_status, attempts, sent_at
FROM telemetry.anomaly_alerts ORDER BY detected_at DESC;
```

Set `ALERTS_ENABLED=false` to stop detection and delivery, or
`ALERTS_GMAIL_ENABLED=false` to retain visible log alerts without sending mail.

## Verification

```sh
./mvnw -Dtest=AnomalyAlertTests,AlertDeliveryTests test
```

The scheduler test runs the real simulator and detector, observes a visible alert
within a few seconds, and verifies Gmail message construction through a mocked
mail transport. Other tests check deduplication, delivery backoff, and TLS settings.
They do not send email to a real account or claim verified inbox delivery.
