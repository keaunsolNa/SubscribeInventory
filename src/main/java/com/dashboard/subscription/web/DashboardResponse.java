package com.dashboard.subscription.web;

import java.time.Instant;
import java.util.List;

import com.dashboard.subscription.domain.ProviderUsage;

import lombok.Builder;
import lombok.Getter;

/**
 * Top-level payload the dashboard consumes: when it was assembled plus one snapshot per provider.
 */
@Getter
@Builder
public class DashboardResponse {

	private final Instant generatedAt;
	private final List<ProviderUsage> providers;
}
