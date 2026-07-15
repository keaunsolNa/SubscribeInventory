package com.dashboard.subscription.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.dashboard.subscription.domain.ApiCredentials;
import com.dashboard.subscription.domain.ProviderUsage;
import com.dashboard.subscription.provider.UsageProvider;
import com.dashboard.subscription.web.DashboardResponse;

/**
 * Fans out to every registered {@link UsageProvider} and folds the results into a single response.
 * Providers never throw, so one failing provider degrades to an ERROR card without sinking the request.
 */
@Service
public class UsageAggregator {

	private final List<UsageProvider> providers;
	private final Clock clock;

	public UsageAggregator(List<UsageProvider> providers, Clock clock) {
		this.providers = providers;
		this.clock = clock;
	}

	public DashboardResponse collect() {
		return collect(Map.of());
	}

	/**
	 * Same fan-out with per-request BYOK credentials keyed by provider id; providers without an
	 * entry fall back to the server-side configuration.
	 */
	public DashboardResponse collect(Map<String, ApiCredentials> credentials) {
		List<ProviderUsage> snapshots = providers.parallelStream()
				.map(provider -> provider.fetch(
						credentials.getOrDefault(provider.providerId(), ApiCredentials.EMPTY)))
				.toList();

		return DashboardResponse.builder()
				.generatedAt(clock.instant())
				.providers(snapshots)
				.build();
	}
}
