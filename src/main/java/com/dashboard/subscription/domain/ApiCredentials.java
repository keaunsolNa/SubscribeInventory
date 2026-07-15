package com.dashboard.subscription.domain;

/**
 * Per-request credential override supplied by a BYOK client. The server never stores these;
 * blank fields fall back to the server-side configuration so self-hosted (env var) setups keep working.
 */
public record ApiCredentials(String apiKey, String teamId) {

	public static final ApiCredentials EMPTY = new ApiCredentials(null, null);
}
