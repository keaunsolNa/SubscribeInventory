package com.dashboard.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.AlertProperties;
import com.dashboard.subscription.domain.MetricType;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;
import com.dashboard.subscription.web.DashboardResponse;

class UsageHistoryStoreTest {

	private UsageHistoryStore store(String projectId) {
		AlertProperties properties = new AlertProperties();
		properties.setProjectId(projectId);
		return new UsageHistoryStore(properties, mock(GcpTokenProvider.class),
				RestClient.builder(),
				Clock.fixed(Instant.parse("2026-07-21T13:30:00Z"), ZoneOffset.UTC));
	}

	private ProviderUsage usage(String id, ProviderStatus status, Double remaining, Double cost) {
		return ProviderUsage.builder()
				.providerId(id)
				.displayName(id)
				.metricType(remaining != null ? MetricType.BALANCE : MetricType.COST)
				.status(status)
				.remaining(remaining)
				.cost(cost)
				.build();
	}

	@Test
	void snapshotKeepsOnlyMeasurableOkProviders() {
		DashboardResponse response = DashboardResponse.builder()
				.generatedAt(Instant.parse("2026-07-21T13:00:00Z"))
				.providers(List.of(
						usage("xai", ProviderStatus.OK, 2.8d, null),
						usage("openai", ProviderStatus.OK, null, 18.42d),
						usage("fal", ProviderStatus.DISABLED, 5.0d, null),
						usage("stability", ProviderStatus.OK, null, null)))
				.build();

		String snapshot = store("project").snapshotJson(response);

		assertThat(snapshot).contains("\"p\":\"xai\"").contains("\"r\":2.8")
				.contains("\"p\":\"openai\"").contains("\"c\":18.42")
				.doesNotContain("fal").doesNotContain("stability");
	}

	@Test
	void snapshotIsNullWhenNothingMeasurable() {
		DashboardResponse response = DashboardResponse.builder()
				.generatedAt(Instant.parse("2026-07-21T13:00:00Z"))
				.providers(List.of(usage("fal", ProviderStatus.DISABLED, 5.0d, null)))
				.build();

		assertThat(store("project").snapshotJson(response)).isNull();
	}

	@Test
	void snapshotRoundTripsThroughParse() {
		UsageHistoryStore store = store("project");
		DashboardResponse response = DashboardResponse.builder()
				.generatedAt(Instant.parse("2026-07-21T13:00:00Z"))
				.providers(List.of(usage("xai", ProviderStatus.OK, 2.8d, null)))
				.build();

		List<UsageHistoryStore.ProviderPoint> entries =
				store.parseSnapshot(store.snapshotJson(response));

		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).providerId()).isEqualTo("xai");
		assertThat(entries.get(0).remaining()).isEqualTo(2.8d);
		assertThat(entries.get(0).cost()).isNull();
	}

	@Test
	void inactiveStoreReadsEmptyAndWritesNothing() {
		UsageHistoryStore store = store("");
		DashboardResponse response = DashboardResponse.builder()
				.generatedAt(Instant.parse("2026-07-21T13:00:00Z"))
				.providers(List.of(usage("xai", ProviderStatus.OK, 2.8d, null)))
				.build();

		store.record("fingerprint", response);

		assertThat(store.isActive()).isFalse();
		assertThat(store.recent("fingerprint", 7)).isEmpty();
	}
}
