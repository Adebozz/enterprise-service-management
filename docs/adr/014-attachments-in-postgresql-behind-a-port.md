# ADR-014: Attachments stored in PostgreSQL behind a content-store port

- **Status:** Accepted
- **Date:** 2026-11-15

## Context

Requesters want to show what they see (a screenshot of an error), and support staff want to keep
evidence with the ticket (a log, a packet capture, a channel scan). Files need the same visibility
rules as the conversation: some are for the requester, some only for the staff handling the
ticket. The project targets S3 on AWS in Phase 3, but runs as Docker Compose today, and the
integration tests use a real PostgreSQL through Testcontainers.

## Decision

- **An attachment is ticket data** in its own `ticket.attachment` package, with the same
  `PUBLIC | INTERNAL` visibility and the same access rules as comments (`TicketAccessPolicy`).
  An internal file is "not found" to anyone who may not see it, like an unknown id.
- **Metadata and bytes are separate.** `attachments` holds name, type, size, SHA-256, uploader and
  visibility; `attachment_contents` holds the bytes. Listing never reads file content.
- **The bytes go through a small port, `AttachmentContentStore`** (`put`, `get`, `delete`). Today
  its only implementation keeps them in PostgreSQL (`bytea`), so a file and its description commit
  or roll back together: no orphaned files, no rows pointing at nothing, no extra container or
  volume to run, and the tests exercise the real thing. The AWS milestone adds an S3
  implementation.
- **The server decides what a file is.** The client's content type is ignored. The extension must be
  on an allow-list (images, PDF, Office documents, zip, text, logs, CSV, JSON; nothing a browser
  would run, such as HTML or SVG), and binary formats must start with their format's signature, so
  a script renamed `photo.png` is refused. Names are reduced to their last path segment without
  control characters.
- **Downloads are always downloads:** `Content-Disposition: attachment`, the stored content type,
  `nosniff` and `no-store`. Nothing uploaded is rendered by the browser as a page of this site.
- **Limits:** 10 MB per file (multipart limit, service check and a database CHECK agree; nginx
  allows 11 MB so the backend gives the precise error), 20 files per ticket. Closed tickets keep
  their files and take no more; whoever added a file, or an admin, may remove it while the ticket
  is open. Adding and removing are audited with the file name, never the content.

## Alternatives considered

- **S3 from the start (with LocalStack locally).** The real target, but it adds a container and an
  AWS SDK before there is any AWS deployment, and a file upload and its database row can no longer
  commit together (an orphan cleanup job is needed). The port keeps that choice open for Phase 3.
- **Files on the container's disk.** Simple, but lost when the container is replaced unless a
  volume is managed, wrong for several backend instances, and not transactional either.
- **Pre-signed upload and download URLs.** The right shape with S3 (the bytes skip the backend), and
  the port can grow into it. Without S3 there is nothing to sign.
- **Virus scanning.** A real deployment should scan uploads (for example S3 with an antivirus
  Lambda, or ClamAV). Out of scope here; listed as a known limitation.

## Consequences

- Database size grows with attachments; 10 MB × 20 files bounds one ticket. Fine for a demo and a
  small organisation; the S3 store is the answer at scale.
- The frontend can't use a plain link to download (the access token lives in memory, not a cookie),
  so it fetches the file and hands it to the browser as a temporary object URL.
- The 20-file limit is checked by counting first, so two uploads at the same instant could both
  pass at 19. The database size CHECK still holds; the count can overshoot by one in that race.
