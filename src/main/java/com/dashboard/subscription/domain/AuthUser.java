package com.dashboard.subscription.domain;

/**
 * Authenticated user identity: the stable Google subject id plus the verified email for display.
 */
public record AuthUser(String id, String email) {
}
