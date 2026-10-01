package com.ademola.esm.auth;

import com.ademola.esm.team.TeamSummary;
import com.ademola.esm.user.Role;
import java.util.List;
import java.util.UUID;

public record MyProfileResponse(UUID id, String email, String displayName, Role role, List<TeamSummary> teams) {}
