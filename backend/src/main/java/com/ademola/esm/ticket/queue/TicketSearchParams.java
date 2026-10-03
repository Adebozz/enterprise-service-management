package com.ademola.esm.ticket.queue;

import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Priority;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Query-string filters for the ticket list. Every field is optional; list fields accept repeated
 * or comma-separated values ({@code ?status=NEW,ASSIGNED}). Dates are inclusive days in UTC.
 */
public record TicketSearchParams(
        TicketView view,
        List<WorkItemType> type,
        List<String> status,
        List<Priority> priority,
        UUID teamId,
        UUID assigneeId,
        UUID categoryId,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
        Boolean open,
        @Size(max = 200) String q) {

    TicketView viewOrDefault() {
        return view == null ? TicketView.ALL : view;
    }

    String trimmedQuery() {
        return q == null || q.isBlank() ? null : q.strip();
    }
}
