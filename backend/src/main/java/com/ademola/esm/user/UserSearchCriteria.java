package com.ademola.esm.user;

/** Optional filters for the admin user search; {@code null} means "don't filter". */
public record UserSearchCriteria(String q, Role role, Boolean active) {}
