# ADR-011: Catalogue forms as JSON data, answers as a snapshot on the request

- **Status:** Accepted
- **Date:** 2026-11-11

## Context

Service-catalogue items ("New laptop", "Shared drive access") each ask their own questions. Admins
add and change items without a deployment. Support needs the answers on the request, and a request
must keep saying what was asked even after the item's form changes. Requests from the catalogue
must otherwise behave like any other service request: the same routing, workflow, SLA and queues.

## Decision

- **A form is a JSON array** (`catalogue_items.form_fields`) of question definitions: key, label,
  type (short text, paragraph, choice, date), required, options and hint. One pure class,
  `CatalogueForm`, holds every rule about valid forms and valid answers, and reports all problems
  at once, per question.
- **Answers are stored on the request** (`service_requests.catalogue_answers`) with the question's
  label copied in, and also written into the description as "Question: answer" lines.
- **Items route through categories**, not teams. Ordering validates the answers, then calls
  `ServiceRequestService.createFromCatalogue`; the `ticket` module never imports `catalogue`.

## Alternatives considered

- **A `catalogue_fields` table and an `answers` table.** Normalised and queryable per question, but
  a form is always read and saved whole and nothing asks "which requests answered X to question
  Y?" yet. Two extra tables, a diff-and-merge on every save, and joins on every read, for no
  current benefit. If reporting by answer becomes a need, the JSON can be indexed (GIN) or moved to
  a table by a migration.
- **Reference the item's current form instead of snapshotting labels.** Smaller rows, but renaming
  or removing a question would silently change or hide what old requests say was asked, which is
  the record support and auditors rely on.
- **Only a description, no structured answers.** Simplest, but answers could never be shown or
  used per question (approvals, fulfilment checklists, reporting).
- **A team on each item.** More direct, but two routing tables that can disagree; a re-routed
  category would leave its catalogue items behind.

## Consequences

- Admins can change forms at any time without affecting existing requests.
- Every existing screen, search and the queue show catalogue answers through the description,
  with no changes.
- Form rules live in Java rather than database constraints; the database only checks that the
  column holds an array. The admin API is the only writer, and `CatalogueForm` runs on every save.
