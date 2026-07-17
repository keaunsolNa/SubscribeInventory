package com.dashboard.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dashboard.subscription.config.AlertProperties;
import com.dashboard.subscription.domain.MetricType;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;

class AlertEvaluatorTest {

	private final AlertProperties properties = new AlertProperties();
	private final AlertEvaluator evaluator = new AlertEvaluator(properties);

	@Test
	void quotaAboveThresholdTriggersAlert() {
		ProviderUsage usage = quota("ElevenLabs", 85.0d, 100.0d);

		List<String> alerts = evaluator.evaluate(List.of(usage));

		assertThat(alerts).hasSize(1);
		assertThat(alerts.get(0)).contains("ElevenLabs").contains("85");
	}

	@Test
	void quotaBelowThresholdStaysSilent() {
		ProviderUsage usage = quota("ElevenLabs", 50.0d, 100.0d);

		assertThat(evaluator.evaluate(List.of(usage))).isEmpty();
	}

	@Test
	void balanceBelowMinimumTriggersAlert() {
		ProviderUsage usage = ProviderUsage.builder()
				.providerId("xai")
				.displayName("xAI (Grok)")
				.metricType(MetricType.BALANCE)
				.status(ProviderStatus.OK)
				.remaining(3.96d)
				.currency("USD")
				.build();

		List<String> alerts = evaluator.evaluate(List.of(usage));

		assertThat(alerts).hasSize(1);
		assertThat(alerts.get(0)).contains("xAI").contains("3.96");
	}

	@Test
	void costAboveLimitTriggersAlertWhenConfigured() {
		properties.setCostMaxUsd(10.0d);
		ProviderUsage usage = cost("OpenAI", 12.34d);

		List<String> alerts = evaluator.evaluate(List.of(usage));

		assertThat(alerts).hasSize(1);
		assertThat(alerts.get(0)).contains("OpenAI").contains("12.34");
	}

	@Test
	void costIgnoredWithoutConfiguredLimit() {
		ProviderUsage usage = cost("OpenAI", 999.0d);

		assertThat(evaluator.evaluate(List.of(usage))).isEmpty();
	}

	@Test
	void nonOkProvidersNeverAlert() {
		ProviderUsage disabled = ProviderUsage.builder()
				.providerId("anthropic")
				.displayName("Anthropic (Claude)")
				.metricType(MetricType.COST)
				.status(ProviderStatus.DISABLED)
				.build();

		assertThat(evaluator.evaluate(List.of(disabled))).isEmpty();
	}

	private ProviderUsage quota(String name, double used, double limit) {
		return ProviderUsage.builder()
				.providerId(name.toLowerCase())
				.displayName(name)
				.metricType(MetricType.QUOTA)
				.status(ProviderStatus.OK)
				.used(used)
				.limit(limit)
				.build();
	}

	private ProviderUsage cost(String name, double cost) {
		return ProviderUsage.builder()
				.providerId(name.toLowerCase())
				.displayName(name)
				.metricType(MetricType.COST)
				.status(ProviderStatus.OK)
				.cost(cost)
				.currency("USD")
				.build();
	}
}
