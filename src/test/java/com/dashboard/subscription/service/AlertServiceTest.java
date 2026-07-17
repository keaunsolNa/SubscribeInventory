package com.dashboard.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dashboard.subscription.config.AlertProperties;
import com.dashboard.subscription.web.DashboardResponse;

class AlertServiceTest {

	private final AlertProperties properties = new AlertProperties();
	private final CachedUsageService cachedUsageService = mock(CachedUsageService.class);
	private final AlertEvaluator alertEvaluator = mock(AlertEvaluator.class);
	private final SlackNotifier slackNotifier = mock(SlackNotifier.class);
	private final AlertService service =
			new AlertService(properties, cachedUsageService, alertEvaluator, slackNotifier);

	@BeforeEach
	void setUp() {
		properties.setWebhookUrl("https://hooks.slack.com/services/T000/B000/XXX");
		when(cachedUsageService.collect(Map.of())).thenReturn(DashboardResponse.builder()
				.generatedAt(Instant.parse("2026-07-17T00:00:00Z"))
				.providers(List.of())
				.build());
	}

	@Test
	void disabledWhenWebhookMissing() {
		properties.setWebhookUrl("");

		AlertCheckResult result = service.check();

		assertThat(result.enabled()).isFalse();
		verify(slackNotifier, never()).send(anyString());
	}

	@Test
	void notifiesWhenAlertsAppear() {
		when(alertEvaluator.evaluate(anyList())).thenReturn(List.of("ElevenLabs: 사용량 85%"));

		AlertCheckResult result = service.check();

		assertThat(result.enabled()).isTrue();
		assertThat(result.notified()).isTrue();
		assertThat(result.alerts()).containsExactly("ElevenLabs: 사용량 85%");
		verify(slackNotifier, times(1)).send(anyString());
	}

	@Test
	void doesNotResendUnchangedAlertState() {
		when(alertEvaluator.evaluate(anyList())).thenReturn(List.of("ElevenLabs: 사용량 85%"));

		service.check();
		AlertCheckResult second = service.check();

		assertThat(second.notified()).isFalse();
		verify(slackNotifier, times(1)).send(anyString());
	}

	@Test
	void notifiesAgainWhenStateChangesAfterRecovery() {
		when(alertEvaluator.evaluate(anyList()))
				.thenReturn(List.of("ElevenLabs: 사용량 85%"))
				.thenReturn(List.of())
				.thenReturn(List.of("ElevenLabs: 사용량 91%"));

		service.check();
		service.check();
		AlertCheckResult third = service.check();

		assertThat(third.notified()).isTrue();
		verify(slackNotifier, times(2)).send(anyString());
	}
}
