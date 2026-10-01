package com.ademola.esm.ticket.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ademola.esm.audit.AuditService;
import com.ademola.esm.common.error.DomainException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.team.TeamService;
import com.ademola.esm.ticket.WorkItemType;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Routing and category-choice rules for new tickets. */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    static final UUID NETWORK_TEAM = UUID.randomUUID();
    static final UUID SECURITY_TEAM = UUID.randomUUID();

    @Mock
    CategoryRepository categories;

    @Mock
    TeamService teams;

    @Mock
    AuditService audit;

    CategoryService service;
    Category network;

    @BeforeEach
    void setUp() {
        service = new CategoryService(categories, teams, audit);
        network = stored(new Category("NETWORK", "Network", null, NETWORK_TEAM, CategoryScope.INCIDENT));
    }

    @Test
    void topLevelCategoryRoutesToItsTeam() {
        CategoryChoice choice = service.chooseForNewTicket(network.getId(), null, WorkItemType.INCIDENT);

        assertThat(choice.routedTeamId()).isEqualTo(NETWORK_TEAM);
        assertThat(choice.subcategoryId()).isNull();
    }

    @Test
    void subcategoryWithoutTeamInheritsTheParentsTeam() {
        Category wifi = stored(new Category("WIFI", "Wi-Fi", network.getId(), null, CategoryScope.INCIDENT));

        assertThat(service.chooseForNewTicket(network.getId(), wifi.getId(), WorkItemType.INCIDENT)
                        .routedTeamId())
                .isEqualTo(NETWORK_TEAM);
    }

    @Test
    void subcategoryTeamOverridesTheParentsTeam() {
        Category firewall =
                stored(new Category("FIREWALL", "Firewall", network.getId(), SECURITY_TEAM, CategoryScope.INCIDENT));

        assertThat(service.chooseForNewTicket(network.getId(), firewall.getId(), WorkItemType.INCIDENT)
                        .routedTeamId())
                .isEqualTo(SECURITY_TEAM);
    }

    @Test
    void categoryForAnotherTicketTypeIsRejected() {
        assertInvalid(() -> service.chooseForNewTicket(network.getId(), null, WorkItemType.SERVICE_REQUEST));
    }

    @Test
    void inactiveCategoryIsRejected() {
        network.deactivate();

        assertInvalid(() -> service.chooseForNewTicket(network.getId(), null, WorkItemType.INCIDENT));
    }

    @Test
    void subcategoryOfAnotherParentIsRejected() {
        Category hardware = stored(new Category("HARDWARE", "Hardware", null, NETWORK_TEAM, CategoryScope.ANY));
        Category laptop = stored(new Category("LAPTOP", "Laptop", hardware.getId(), null, CategoryScope.ANY));

        assertInvalid(() -> service.chooseForNewTicket(network.getId(), laptop.getId(), WorkItemType.INCIDENT));
    }

    @Test
    void subcategoryCannotBeUsedAsTheTopLevelCategory() {
        Category wifi = stored(new Category("WIFI", "Wi-Fi", network.getId(), null, CategoryScope.INCIDENT));

        assertInvalid(() -> service.chooseForNewTicket(wifi.getId(), null, WorkItemType.INCIDENT));
    }

    @Test
    void unknownCategoryIsReportedAsInvalidNotNotFound() {
        UUID unknown = UUID.randomUUID();
        when(categories.findById(unknown)).thenReturn(Optional.empty());

        assertInvalid(() -> service.chooseForNewTicket(unknown, null, WorkItemType.INCIDENT));
    }

    @Test
    void anyScopeCategoryAcceptsEveryTicketType() {
        Category access = stored(new Category("ACCESS", "Access", null, NETWORK_TEAM, CategoryScope.ANY));

        assertThat(service.chooseForNewTicket(access.getId(), null, WorkItemType.SERVICE_REQUEST))
                .isNotNull();
        assertThat(service.chooseForNewTicket(access.getId(), null, WorkItemType.INCIDENT))
                .isNotNull();
    }

    private Category stored(Category category) {
        ReflectionTestUtils.setField(category, "id", UUID.randomUUID());
        org.mockito.Mockito.lenient()
                .when(categories.findById(category.getId()))
                .thenReturn(Optional.of(category));
        return category;
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.INVALID_CATEGORY);
    }
}
