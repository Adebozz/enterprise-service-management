package com.ademola.esm.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditRecord;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class NotificationRulesTest {

    static final UUID REQUESTER = UUID.randomUUID();
    static final UUID AGENT = UUID.randomUUID();
    static final UUID OTHER_AGENT = UUID.randomUUID();
    static final UUID LEAD = UUID.randomUUID();
    static final UUID TEAM = UUID.randomUUID();
    static final UUID TICKET = UUID.randomUUID();

    static final TicketFacts ASSIGNED = new TicketFacts(TICKET, "INC-000001", "Wi-Fi down", REQUESTER, AGENT, TEAM);
    static final TicketFacts UNASSIGNED = new TicketFacts(TICKET, "INC-000001", "Wi-Fi down", REQUESTER, null, TEAM);
    static final Function<UUID, Set<UUID>> LEADS = team -> team.equals(TEAM) ? Set.of(LEAD) : Set.of();

    @Test
    void theNewAssigneeHearsUnlessTheyTookItThemselves() {
        AuditRecord assigned = assignment(null, OTHER_AGENT);

        assertThat(decide(assigned, LEAD, "Leo Lead", ASSIGNED)).singleElement().satisfies(draft -> {
            assertThat(draft.recipientId()).isEqualTo(OTHER_AGENT);
            assertThat(draft.kind()).isEqualTo(NotificationKind.ASSIGNED);
            assertThat(draft.message()).isEqualTo("Leo Lead assigned you INC-000001: Wi-Fi down");
        });
        assertThat(decide(assigned, OTHER_AGENT, "Olga", ASSIGNED)).isEmpty();
        assertThat(decide(assignment(OTHER_AGENT, null), LEAD, "Leo", ASSIGNED)).isEmpty();
        assertThat(decide(assignment(OTHER_AGENT, OTHER_AGENT), LEAD, "Leo", ASSIGNED))
                .as("team transfer only")
                .isEmpty();
    }

    @Test
    void statusChangesReachTheRequesterAndTheAssigneeButNotWhoeverMadeThem() {
        AuditRecord resolved = status("RESOLVED", Map.of("transition", "Resolve"));

        assertThat(decide(resolved, AGENT, "Nina", ASSIGNED)).singleElement().satisfies(draft -> {
            assertThat(draft.recipientId()).isEqualTo(REQUESTER);
            assertThat(draft.message()).isEqualTo("Nina moved INC-000001 to resolved: Wi-Fi down");
        });
        assertThat(recipients(decide(status("CANCELLED", Map.of()), REQUESTER, "Rita", ASSIGNED)))
                .containsExactly(AGENT);
        assertThat(decide(status("APPROVED", Map.of()), null, null, ASSIGNED))
                .extracting(NotificationDraft::message)
                .containsOnly("INC-000001 is now approved: Wi-Fi down");
    }

    @Test
    void aRequestersReplyIsNewsOnceNotTwice() {
        assertThat(decide(status("IN_PROGRESS", Map.of("trigger", "REQUESTER_REPLY")), REQUESTER, "Rita", ASSIGNED))
                .isEmpty();
        assertThat(decide(comment("PUBLIC", false), REQUESTER, "Rita", ASSIGNED))
                .singleElement()
                .satisfies(draft -> assertThat(draft.message()).isEqualTo("Rita replied on INC-000001: Wi-Fi down"));
        assertThat(decide(comment("PUBLIC", true), AGENT, "Nina", ASSIGNED))
                .as("the reason of a status change")
                .isEmpty();
    }

    @Test
    void internalNotesAndFilesNeverReachTheRequester() {
        assertThat(recipients(decide(comment("INTERNAL", false), OTHER_AGENT, "Olga", ASSIGNED)))
                .containsExactly(AGENT);
        assertThat(decide(comment("INTERNAL", false), AGENT, "Nina", ASSIGNED)).isEmpty();

        AuditRecord internalFile = AuditRecord.event(
                AuditAction.ATTACHMENT_ADDED,
                AuditEntityType.WORK_ITEM,
                TICKET,
                Map.of("fileName", "scan.csv", "visibility", "INTERNAL"));
        assertThat(recipients(decide(internalFile, OTHER_AGENT, "Olga", ASSIGNED)))
                .containsExactly(AGENT);

        AuditRecord publicFile = AuditRecord.event(
                AuditAction.ATTACHMENT_ADDED,
                AuditEntityType.WORK_ITEM,
                TICKET,
                Map.of("fileName", "error.png", "visibility", "PUBLIC"));
        assertThat(decide(publicFile, REQUESTER, "Rita", ASSIGNED))
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft.kind()).isEqualTo(NotificationKind.FILE_ADDED);
                    assertThat(draft.message()).isEqualTo("Rita attached error.png to INC-000001: Wi-Fi down");
                });
    }

    @Test
    void aBreachGoesToTheAssigneeOrToTheTeamLeadsWhileNobodyOwnsIt() {
        AuditRecord breach = AuditRecord.event(
                AuditAction.SLA_BREACHED, AuditEntityType.WORK_ITEM, TICKET, Map.of("metric", "RESPONSE"));

        assertThat(recipients(decide(breach, null, null, ASSIGNED))).containsExactly(AGENT);
        assertThat(decide(breach, null, null, UNASSIGNED)).singleElement().satisfies(draft -> {
            assertThat(draft.recipientId()).isEqualTo(LEAD);
            assertThat(draft.message()).isEqualTo("INC-000001 missed its response target: Wi-Fi down");
        });
    }

    @Test
    void approvalsAreAskedOfTheTeamsLeadsNeverOfTheRequester() {
        AuditRecord requested = AuditRecord.created(
                AuditAction.APPROVAL_REQUESTED,
                AuditEntityType.APPROVAL,
                UUID.randomUUID(),
                Map.of("workItemId", TICKET));

        assertThat(NotificationRules.ticketIdOf(requested)).isEqualTo(TICKET);
        assertThat(recipients(decide(requested, REQUESTER, "Rita", UNASSIGNED))).containsExactly(LEAD);
        TicketFacts raisedByLead = new TicketFacts(TICKET, "REQ-000001", "Access", LEAD, null, TEAM);
        assertThat(decide(requested, LEAD, "Leo", raisedByLead)).isEmpty();
    }

    @Test
    void entriesAboutAnythingButTicketsAreIgnored() {
        AuditRecord userCreated =
                AuditRecord.created(AuditAction.USER_CREATED, AuditEntityType.USER, UUID.randomUUID(), Map.of());
        assertThat(NotificationRules.ticketIdOf(userCreated)).isNull();
        assertThat(decide(
                        AuditRecord.event(AuditAction.TICKET_CREATED, AuditEntityType.WORK_ITEM, TICKET, Map.of()),
                        REQUESTER,
                        "Rita",
                        ASSIGNED))
                .isEmpty();
        AuditRecord approvalWithoutTicket = AuditRecord.created(
                AuditAction.APPROVAL_REQUESTED, AuditEntityType.APPROVAL, UUID.randomUUID(), Map.of());
        assertThat(NotificationRules.ticketIdOf(approvalWithoutTicket)).isNull();
    }

    @Test
    void anUnknownActorIsSomeoneAndAMissingStatusStillReads() {
        assertThat(decide(comment("PUBLIC", false), OTHER_AGENT, null, ASSIGNED))
                .extracting(NotificationDraft::message)
                .containsOnly("Someone replied on INC-000001: Wi-Fi down");
        assertThat(NotificationRules.statusLabel("WAITING_FOR_USER")).isEqualTo("waiting for user");
        assertThat(NotificationRules.statusLabel(null)).isEqualTo("a new status");
    }

    private static List<NotificationDraft> decide(AuditRecord record, UUID actor, String name, TicketFacts ticket) {
        return NotificationRules.decide(record, actor, name, ticket, LEADS);
    }

    private static List<UUID> recipients(List<NotificationDraft> drafts) {
        return drafts.stream().map(NotificationDraft::recipientId).toList();
    }

    private static AuditRecord assignment(UUID before, UUID after) {
        Map<String, Object> old = new HashMap<>();
        old.put("assigneeId", before);
        Map<String, Object> now = new HashMap<>();
        now.put("assigneeId", after);
        return AuditRecord.changed(AuditAction.TICKET_ASSIGNMENT_CHANGED, AuditEntityType.WORK_ITEM, TICKET, old, now);
    }

    private static AuditRecord status(String status, Map<String, ?> metadata) {
        return new AuditRecord(
                AuditAction.TICKET_STATUS_CHANGED,
                AuditEntityType.WORK_ITEM,
                TICKET,
                Map.of("status", "IN_PROGRESS"),
                Map.of("status", status),
                metadata);
    }

    private static AuditRecord comment(String visibility, boolean explainsStatus) {
        return AuditRecord.event(
                AuditAction.COMMENT_ADDED,
                AuditEntityType.WORK_ITEM,
                TICKET,
                explainsStatus
                        ? Map.of("visibility", visibility, "relatedStatus", "WAITING_FOR_USER")
                        : Map.of("visibility", visibility));
    }
}
