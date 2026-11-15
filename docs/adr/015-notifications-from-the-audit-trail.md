# ADR-015: Notifications derived from the audit trail, emailed through an outbox

- **Status:** Accepted
- **Date:** 2026-11-15

## Context

Breaches, replies and approvals were recorded and shown, but nobody was told: a requester had to
keep checking their ticket, and a team lead found pending approvals only by opening the inbox.
Every change worth telling someone about is already written to the audit trail, with who did it,
inside the change's transaction (ADR-006).

## Decision

- **The audit trail drives notifications.** `AuditService` publishes an `AuditRecordedEvent` for
  every entry it writes, synchronously and in the same transaction. The `notification` module
  listens and `NotificationRules`, a pure function, decides who hears what. No feature calls the
  notification module, and new auditable actions only need a rule to notify.
- **Rules:** the new assignee; the requester and assignee on a status change; the requester and
  assignee on a public reply or file, only the assignee on internal ones; the assignee on an SLA
  breach, or the team's leads while nobody owns the ticket; the team's leads when an approval is
  waiting (never whoever raised the ticket). Nobody is told about their own action (also a database
  CHECK), and one change tells each person once: a status change's reason and a status change
  caused by the requester's reply don't notify twice.
- **The message is written when the notification is created** ("Nina Network replied on
  INC-000001: Wi-Fi keeps dropping"), so the inbox needs no joins and stays readable if the ticket
  changes later.
- **Email goes through an outbox.** Each notification row also records its email state. A
  scheduled job locks a batch of `PENDING` rows (`FOR UPDATE SKIP LOCKED`, so several instances
  never send the same row), sends them through a `MailGateway` (SMTP via Spring's
  `JavaMailSender`), and records `SENT`, or retries up to three times and then `FAILED`. A mail
  server being slow or down never delays or rolls back a ticket change. Delivery is at least once.
- **Email is off unless configured.** The Compose stack turns it on and points it at Mailpit, which
  catches every email in a web inbox. Tests replace the gateway with one that records messages.

## Alternatives considered

- **Calling a notification service from each feature.** Explicit, but every feature has to
  remember, the calls repeat what the audit entry already says, and `ticket` would depend on
  `notification`.
- **Sending email inside the change's transaction.** Simple, but a slow SMTP server holds the
  transaction open, and a rollback after sending leaves an email about something that didn't
  happen.
- **A message broker (SQS, Kafka) for the outbox.** The right next step with several services; for
  one application and one database, a table and a scheduled job give the same guarantees with
  nothing extra to run.
- **Pushing to the browser (WebSocket or server-sent events).** Instant, but long-lived connections
  for a badge that is fine refreshed every 30 seconds. Polling the unread count is one cheap,
  indexed query.

## Consequences

- Every audit entry now costs one in-memory event and, for ticket entries, a ticket lookup (already
  in the persistence context in almost every case).
- Notifications are kept indefinitely; a retention job and per-person preferences (which kinds,
  email or not) are future work.
- The email body is plain text with a link to the ticket; no comment text or files leave the
  system by email.
