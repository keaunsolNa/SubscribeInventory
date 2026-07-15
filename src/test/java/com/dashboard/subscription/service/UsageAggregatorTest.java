package com.dashboard.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dashboard.subscription.domain.ApiCredentials;
import com.dashboard.subscription.domain.MetricType;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;
import com.dashboard.subscription.provider.UsageProvider;
import com.dashboard.subscription.web.DashboardResponse;

class UsageAggregatorTest {

	private static final Instant NOW = Instant.parse("2026-07-15T09:00:00Z");
	private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void collectGathersEverySnapshotAndStampsGeneratedAt() {
		UsageProvider first = mock(UsageProvider.class);
		UsageProvider second = mock(UsageProvider.class);
		when(first.providerId()).thenReturn("elevenlabs");
		when(second.providerId()).thenReturn("openai");
		when(first.fetch(ApiCredentials.EMPTY)).thenReturn(snapshot("elevenlabs", ProviderStatus.OK));
		when(second.fetch(ApiCredentials.EMPTY)).thenReturn(snapshot("openai", ProviderStatus.ERROR));

		UsageAggregator aggregator = new UsageAggregator(List.of(first, second), FIXED_CLOCK);
		DashboardResponse response = aggregator.collect();

		assertThat(response.getGeneratedAt()).isEqualTo(NOW);
		assertThat(response.getProviders())
				.extracting(ProviderUsage::getProviderId)
				.containsExactlyInAnyOrder("elevenlabs", "openai");
	}

	@Test
	void collectRoutesRequestCredentialsToMatchingProvider() {
		UsageProvider xai = mock(UsageProvider.class);
		UsageProvider openai = mock(UsageProvider.class);
		when(xai.providerId()).thenReturn("xai");
		when(openai.providerId()).thenReturn("openai");
		ApiCredentials credentials = new ApiCredentials("byok-key", "team_byok");
		when(xai.fetch(credentials)).thenReturn(snapshot("xai", ProviderStatus.OK));
		when(openai.fetch(ApiCredentials.EMPTY)).thenReturn(snapshot("openai", ProviderStatus.DISABLED));

		UsageAggregator aggregator = new UsageAggregator(List.of(xai, openai), FIXED_CLOCK);
		DashboardResponse response = aggregator.collect(Map.of("xai", credentials));

		assertThat(response.getProviders())
				.extracting(ProviderUsage::getProviderId, ProviderUsage::getStatus)
				.containsExactlyInAnyOrder(
						org.assertj.core.groups.Tuple.tuple("xai", ProviderStatus.OK),
						org.assertj.core.groups.Tuple.tuple("openai", ProviderStatus.DISABLED));
	}

	@Test
	void collectReturnsEmptyListWhenNoProviders() {
		UsageAggregator aggregator = new UsageAggregator(List.of(), FIXED_CLOCK);

		DashboardResponse response = aggregator.collect();

		assertThat(response.getProviders()).isEmpty();
		assertThat(response.getGeneratedAt()).isEqualTo(NOW);
	}

	private ProviderUsage snapshot(String providerId, ProviderStatus status) {
		return ProviderUsage.builder()
				.providerId(providerId)
				.metricType(MetricType.COST)
				.status(status)
				.build();
	}
}
