package com.ademola.esm.team;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TeamMemberRepository extends JpaRepository<TeamMember, TeamMemberId> {

    /**
     * Members with their user details in a single query, projected straight into the response DTO.
     * Fetching memberships and then each user separately would be the classic N+1 problem.
     */
    @Query("""
            select new com.ademola.esm.team.TeamMemberResponse(u.id, u.displayName, u.email, u.role, u.active, m.joinedAt)
            from TeamMember m join User u on u.id = m.id.userId
            where m.id.teamId = :teamId
            order by lower(u.displayName)
            """)
    List<TeamMemberResponse> findMembers(UUID teamId);

    @Query("""
            select new com.ademola.esm.team.TeamSummary(t.id, t.name)
            from TeamMember m join Team t on t.id = m.id.teamId
            where m.id.userId = :userId and t.active = true
            order by t.name
            """)
    List<TeamSummary> findActiveTeamsOf(UUID userId);

    /** Bulk delete: one SQL statement, no need to load the rows first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TeamMember m where m.id.userId = :userId")
    int deleteAllByUserId(UUID userId);
}
