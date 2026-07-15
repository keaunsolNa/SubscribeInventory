package com.dashboard.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dashboard.subscription.domain.ApiCredentials;
import com.dashboard.subscription.web.DashboardResponse;

class CachedUsageServiceTest {

	private static final Instant START = Instant.parse("2026-07-15T09:00:00Z");

	private final MutableClock clock = new MutableClock(START);
	private final UsageAggregator usageAggregator = mock(UsageAggregator.class);
	private final CachedUsageService service = new CachedUsageService(usageAggregator, clock);

	@Test
	void reusesCachedResponseWithinTtl() {
		Map<String, ApiCredentials> credentials = Map.of("xai", new ApiCredentials("key", "team"));
		DashboardResponse first = response();
		when(usageAggregator.collect(credentials)).thenReturn(first);

		DashboardResponse initial = service.collect(credentials);
		clock.advance(Duration.ofSeconds(30));
		DashboardResponse cached = service.collect(credentials);

		assertThat(initial).isSameAs(first);
		assertThat(cached).isSameAs(first);
		verify(usageAggregator, times(1)).collect(credentials);
	}

	@Test
	void refreshesAfterTtlExpires() {
		Map<String, ApiCredentials> credentials = Map.of();
		when(usageAggregator.collect(credentials)).thenReturn(response(), response());

		service.collect(credentials);
		clock.advance(Duration.ofSeconds(61));
		service.collect(credentials);

		verify(usageAggregator, times(2)).collect(credentials);
	}

	@Test
	void differentCredentialsGetSeparateEntries() {
		Map<String, ApiCredentials> first = Map.of("xai", new ApiCredentials("key-a", null));
		Map<String, ApiCredentials> second = Map.of("xai", new ApiCredentials("key-b", null));
		when(usageAggregator.collect(first)).thenReturn(response());
		when(usageAggregator.collect(second)).thenReturn(response());

		service.collect(first);
		service.collect(second);
		service.collect(first);
		service.collect(second);

		verify(usageAggregator, times(1)).collect(first);
		verify(usageAggregator, times(1)).collect(second);
	}

	private DashboardResponse response() {
		return DashboardResponse.builder()
				.generatedAt(clock.instant())
				.providers(java.util.List.of())
				.build();
	}

	/**
	 * Test clock that can be advanced manually; keeps the cache deterministic without sleeping.
	 */
	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant start) {
			this.now = start;
		}

		private void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
