package com.ademola.esm.demo;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditRecord;
import com.ademola.esm.audit.AuditService;
import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.team.CreateTeamRequest;
import com.ademola.esm.team.TeamService;
import com.ademola.esm.ticket.assignment.AssignmentRequest;
import com.ademola.esm.ticket.assignment.TicketAssignmentService;
import com.ademola.esm.ticket.category.CategoryScope;
import com.ademola.esm.ticket.category.CategoryService;
import com.ademola.esm.ticket.category.CreateCategoryRequest;
import com.ademola.esm.ticket.comment.AddCommentRequest;
import com.ademola.esm.ticket.comment.CommentService;
import com.ademola.esm.ticket.comment.CommentVisibility;
import com.ademola.esm.ticket.incident.CreateIncidentRequest;
import com.ademola.esm.ticket.incident.IncidentService;
import com.ademola.esm.ticket.incident.ResolutionCode;
import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Urgency;
import com.ademola.esm.ticket.request.CreateServiceRequestRequest;
import com.ademola.esm.ticket.request.ServiceRequestService;
import com.ademola.esm.ticket.transition.TicketTransitionService;
import com.ademola.esm.ticket.transition.TransitionRequest;
import com.ademola.esm.user.CreateUserRequest;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import com.ademola.esm.user.UserRepository;
import com.ademola.esm.user.UserService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Demo data for local runs and demos (profile {@code demo} only, never production).
 *
 * <p>Everything except the first admin is created <b>through the application's own services</b>,
 * signed in as the relevant person at each step. So demo tickets are routed, prioritised, assigned
 * and transitioned by the real rules, and each has a genuine audit history. A seeder that inserted
 * rows directly could create states the application itself would never allow.
 *
 * <p>Idempotent: if the demo admin already exists, nothing happens.
 */
@Component
@Profile("demo")
@Order(100) // after the regular admin bootstrap
class DemoDataSeeder implements ApplicationRunner {

    static final String ADMIN_EMAIL = "admin@demo.local";
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final DemoProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final UserService users;
    private final TeamService teams;
    private final CategoryService categories;
    private final IncidentService incidents;
    private final ServiceRequestService serviceRequests;
    private final TicketAssignmentService assignments;
    private final TicketTransitionService transitions;
    private final CommentService comments;

    DemoDataSeeder(
            DemoProperties properties,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuditService audit,
            TransactionTemplate transaction,
            UserService users,
            TeamService teams,
            CategoryService categories,
            IncidentService incidents,
            ServiceRequestService serviceRequests,
            TicketAssignmentService assignments,
            TicketTransitionService transitions,
            CommentService comments) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.transaction = transaction;
        this.users = users;
        this.teams = teams;
        this.categories = categories;
        this.incidents = incidents;
        this.serviceRequests = serviceRequests;
        this.assignments = assignments;
        this.transitions = transitions;
        this.comments = comments;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = properties.password();
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "The demo profile needs ESM_DEMO_PASSWORD (at least " + MIN_PASSWORD_LENGTH + " characters)");
        }
        if (userRepository.existsByEmail(ADMIN_EMAIL)) {
            log.info("Demo data already present; skipping");
            return;
        }
        seed(password);
        log.info("Demo data created: sign in as {} or the other @demo.local accounts", ADMIN_EMAIL);
    }

    private void seed(String password) {
        CurrentUser admin = createFirstAdmin(password);

        // People, teams and categories, created by the admin through the admin services.
        Map<String, CurrentUser> people = as(
                admin,
                () -> Map.of(
                        "lead", person("lead@demo.local", "Leo Lead", Role.TEAM_LEAD, password),
                        "nina", person("nina@demo.local", "Nina Network", Role.AGENT, password),
                        "hugo", person("hugo@demo.local", "Hugo Hardware", Role.AGENT, password),
                        "iris", person("iris@demo.local", "Iris Identity", Role.AGENT, password),
                        "rita", person("rita@demo.local", "Rita Requester", Role.REQUESTER, password),
                        "sam", person("sam@demo.local", "Sam Sales", Role.REQUESTER, password)));
        CurrentUser lead = people.get("lead");
        CurrentUser nina = people.get("nina");
        CurrentUser hugo = people.get("hugo");
        CurrentUser iris = people.get("iris");
        CurrentUser rita = people.get("rita");
        CurrentUser sam = people.get("sam");

        UUID network = as(
                admin,
                () -> teams.create(new CreateTeamRequest("Network Team", "LAN, Wi-Fi and VPN"))
                        .id());
        UUID hardware = as(
                admin,
                () -> teams.create(new CreateTeamRequest("Hardware Team", "Laptops, printers, peripherals"))
                        .id());
        UUID identity = as(
                admin,
                () -> teams.create(new CreateTeamRequest("Identity & Access", "Accounts and permissions"))
                        .id());
        as(admin, () -> {
            teams.addMember(network, lead.id());
            teams.addMember(network, nina.id());
            teams.addMember(hardware, hugo.id());
            teams.addMember(identity, iris.id());
            return null;
        });

        UUID netCat = category(admin, "NETWORK", "Network", null, network, CategoryScope.INCIDENT);
        UUID wifi = category(admin, "WIFI", "Wi-Fi", netCat, null, CategoryScope.INCIDENT);
        UUID vpn = category(admin, "VPN", "VPN", netCat, null, CategoryScope.INCIDENT);
        UUID hwCat = category(admin, "HARDWARE", "Hardware", null, hardware, CategoryScope.ANY);
        UUID laptop = category(admin, "LAPTOP", "Laptop", hwCat, null, CategoryScope.ANY);
        UUID printer = category(admin, "PRINTER", "Printer", hwCat, null, CategoryScope.ANY);
        UUID access = category(admin, "ACCESS", "Access & accounts", null, identity, CategoryScope.SERVICE_REQUEST);
        UUID newAccount = category(admin, "NEW_ACCOUNT", "New account", access, null, CategoryScope.SERVICE_REQUEST);
        UUID drive =
                category(admin, "SHARED_DRIVE", "Shared drive access", access, null, CategoryScope.SERVICE_REQUEST);

        // 1. P2 incident: support is waiting for the requester's answer.
        UUID wifiTicket = incident(
                rita,
                "Wi-Fi keeps dropping on floor 3",
                "Disconnects every few minutes, worst near the kitchen.",
                netCat,
                wifi,
                Impact.MEDIUM,
                Urgency.HIGH);
        long v = assign(nina, wifiTicket, network, nina.id(), 0);
        v = move(nina, wifiTicket, v, "IN_PROGRESS", null, null, null);
        comment(nina, wifiTicket, "Access point AP-3F-02 shows interference on channel 6.", CommentVisibility.INTERNAL);
        move(
                nina,
                wifiTicket,
                v,
                "WAITING_FOR_USER",
                "Which desk number are you at, and does it happen on a cable too?",
                null,
                null);
        comment(rita, wifiTicket, "Desk 3.14. On a cable it's fine.", CommentVisibility.PUBLIC);

        // 2. P1 incident waiting in the unassigned queue.
        incident(
                sam,
                "VPN won't connect from home",
                "Error 809 since this morning; the whole sales team is affected.",
                netCat,
                vpn,
                Impact.HIGH,
                Urgency.HIGH);

        // 3. Resolved incident awaiting the requester's confirmation.
        UUID battery = incident(
                rita,
                "Laptop battery swelling",
                "The trackpad is lifting; the laptop gets hot.",
                hwCat,
                laptop,
                Impact.LOW,
                Urgency.HIGH);
        v = assign(hugo, battery, hardware, hugo.id(), 0);
        v = move(hugo, battery, v, "IN_PROGRESS", null, null, null);
        move(
                hugo,
                battery,
                v,
                "RESOLVED",
                null,
                ResolutionCode.WORKAROUND,
                "Loan laptop issued; battery replacement ordered.");

        // 4. Closed incident: resolved by support, confirmed by the requester.
        UUID printerTicket = incident(
                sam,
                "Printer on floor 2 offline",
                "Shows offline for everyone on floor 2.",
                hwCat,
                printer,
                Impact.MEDIUM,
                Urgency.MEDIUM);
        v = assign(hugo, printerTicket, hardware, hugo.id(), 0);
        v = move(hugo, printerTicket, v, "IN_PROGRESS", null, null, null);
        comment(hugo, printerTicket, "Print spooler crashed on PRN-SRV-02.", CommentVisibility.INTERNAL);
        v = move(
                hugo,
                printerTicket,
                v,
                "RESOLVED",
                null,
                ResolutionCode.FIXED,
                "Restarted the print spooler; printer is back online.");
        move(sam, printerTicket, v, "CLOSED", null, null, null);

        // 5. Fulfilled service request.
        UUID driveRequest = request(
                rita,
                "Access to the Finance shared drive",
                "Read access for the quarterly reports, approved by my manager.",
                access,
                drive,
                Urgency.MEDIUM);
        v = assign(iris, driveRequest, identity, iris.id(), 0);
        v = move(iris, driveRequest, v, "IN_PROGRESS", null, null, null);
        move(
                iris,
                driveRequest,
                v,
                "FULFILLED",
                null,
                null,
                "Added to FIN-Reports-Read; may take 15 minutes to apply.");

        // 6. Urgent service request in the unassigned queue.
        request(
                sam,
                "Account for new starter Jo Patel",
                "Starts Monday in Sales: email, Teams and CRM.",
                access,
                newAccount,
                Urgency.HIGH);

        // 7. Incident assigned by the team lead, not yet started.
        UUID teamsCalls = incident(
                sam,
                "Teams calls are choppy",
                "Audio breaks up in every call since the update.",
                netCat,
                null,
                Impact.MEDIUM,
                Urgency.MEDIUM);
        assign(lead, teamsCalls, network, nina.id(), 0);

        // 8. Cancelled by the requester.
        UUID signature = incident(
                rita,
                "Email signature lost",
                "My signature disappeared after the update.",
                hwCat,
                null,
                Impact.LOW,
                Urgency.LOW);
        move(rita, signature, 0, "CANCELLED", "Found it under Settings, all good now.", null, null);
    }

    // ----- helpers ------------------------------------------------------------------------------

    /** The only record created directly: there is no admin yet to create it through the API. */
    private CurrentUser createFirstAdmin(String password) {
        User admin = transaction.execute(status -> {
            User created = userRepository.saveAndFlush(
                    new User(ADMIN_EMAIL, "Ada Admin", passwordEncoder.encode(password), Role.ADMIN));
            audit.record(AuditRecord.created(
                    AuditAction.USER_CREATED,
                    AuditEntityType.USER,
                    created.getId(),
                    Map.of("email", ADMIN_EMAIL, "role", Role.ADMIN, "source", "DEMO_SEED")));
            return created;
        });
        return new CurrentUser(admin.getId(), Role.ADMIN, admin.getDisplayName());
    }

    private CurrentUser person(String email, String name, Role role, String password) {
        var created = users.create(new CreateUserRequest(email, name, role, password));
        return new CurrentUser(created.id(), role, name);
    }

    private UUID category(CurrentUser admin, String code, String name, UUID parent, UUID team, CategoryScope scope) {
        return as(
                admin,
                () -> categories
                        .create(new CreateCategoryRequest(code, name, parent, team, scope))
                        .id());
    }

    private UUID incident(
            CurrentUser who,
            String title,
            String description,
            UUID category,
            UUID sub,
            Impact impact,
            Urgency urgency) {
        return as(
                who,
                () -> incidents
                        .create(
                                who,
                                new CreateIncidentRequest(title, description, category, sub, impact, urgency, null))
                        .id());
    }

    private UUID request(CurrentUser who, String title, String description, UUID category, UUID sub, Urgency urgency) {
        return as(
                who,
                () -> serviceRequests
                        .create(who, new CreateServiceRequestRequest(title, description, category, sub, urgency))
                        .id());
    }

    private long assign(CurrentUser who, UUID ticket, UUID team, UUID assignee, long version) {
        return as(
                who,
                () -> assignments
                        .assign(who, ticket, new AssignmentRequest(team, assignee, version))
                        .version());
    }

    private long move(
            CurrentUser who,
            UUID ticket,
            long version,
            String target,
            String reason,
            ResolutionCode code,
            String notes) {
        return as(
                who,
                () -> transitions
                        .transition(who, ticket, new TransitionRequest(target, version, reason, code, notes))
                        .version());
    }

    private void comment(CurrentUser who, UUID ticket, String body, CommentVisibility visibility) {
        as(who, () -> comments.add(who, ticket, new AddCommentRequest(body, visibility)));
    }

    /** Runs a step signed in as {@code user}, so permissions and audit actors are real. */
    private <T> T as(CurrentUser user, Supplier<T> step) {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        user,
                        null,
                        List.of(new SimpleGrantedAuthority(user.role().authority()))));
        try {
            return step.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
