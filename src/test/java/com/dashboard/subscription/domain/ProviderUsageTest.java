package com.dashboard.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderUsageTest {

	@Test
	void usedPercentComputedForQuota() {
		ProviderUsage usage = ProviderUsage.builder()
				.metricType(MetricType.QUOTA)
				.used(25.0d)
				.limit(100.0d)
				.build();

		assertThat(usage.getUsedPercent()).isEqualTo(25.0d);
	}

	@Test
	void usedPercentClampedToHundred() {
		ProviderUsage usage = ProviderUsage.builder()
				.metricType(MetricType.QUOTA)
				.used(150.0d)
				.limit(100.0d)
				.build();

		assertThat(usage.getUsedPercent()).isEqualTo(100.0d);
	}

	@Test
	void usedPercentNullWhenLimitZero() {
		ProviderUsage usage = ProviderUsage.builder()
				.metricType(MetricType.QUOTA)
				.used(10.0d)
				.limit(0.0d)
				.build();

		assertThat(usage.getUsedPercent()).isNull();
	}

	@Test
	void usedPercentNullForNonQuotaMetric() {
		ProviderUsage usage = ProviderUsage.builder()
				.metricType(MetricType.COST)
				.cost(42.0d)
				.build();

		assertThat(usage.getUsedPercent()).isNull();
	}
}
