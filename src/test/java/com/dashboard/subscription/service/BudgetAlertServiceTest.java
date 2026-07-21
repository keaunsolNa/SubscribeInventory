package com.dashboard.subscription.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.dashboard.subscription.config.AlertProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class BudgetAlertServiceTest {

	private static final String WEBHOOK = "https://hooks.slack.com/services/T/B/x";

	private final AlertProperties properties = new AlertProperties();
	private final SlackNotifier slackNotifier = mock(SlackNotifier.class);
	private final BudgetStateStore stateStore = mock(BudgetStateStore.class);
	private final BudgetAlertService service = new BudgetAlertService(properties, slackNotifier,
			stateStore, Clock.fixed(Instant.parse("2026-07-21T13:00:00Z"), ZoneOffset.UTC));
	private final ObjectMapper objectMapper = new ObjectMapper();

	private JsonNode envelope(String budgetJson) {
		String data = Base64.getEncoder()
				.encodeToString(budgetJson.getBytes(StandardCharsets.UTF_8));
		try {
			return objectMapper.readTree("{\"message\":{\"data\":\"" + data + "\"}}");
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private void activeStore(Optional<BudgetStateStore.State> state) {
		properties.setProjectId("project");
		properties.setBudgetSlackWebhook(WEBHOOK);
		when(stateStore.isActive()).thenReturn(true);
		when(stateStore.read()).thenReturn(state);
	}

	@Test
	void ignoresRoutineSpendUpdatesWithoutThreshold() {
		activeStore(Optional.empty());

		service.handle(envelope("{\"costAmount\":1200.0,\"budgetAmount\":10000.0,"
				+ "\"currencyCode\":\"KRW\"}"));

		verify(slackNotifier, never()).send(anyString(), anyString());
		verify(stateStore).saveLatest(new BudgetStateStore.LatestSpend(
				1200.0, 10000.0, "KRW", "2026-07-21T13:00:00Z"));
	}

	@Test
	void weeklyReportSendsLatestSpend() {
		properties.setBudgetSlackWebhook(WEBHOOK);
		when(stateStore.readLatest()).thenReturn(Optional.of(
				new BudgetStateStore.LatestSpend(150.0, 10000.0, "KRW", "2026-07-21T13:00:00Z")));

		service.sendWeeklyReport();

		verify(slackNotifier).send(eq(WEBHOOK), contains("주간 GCP 사용액 리포트"));
		verify(slackNotifier).send(eq(WEBHOOK), contains("1.5%"));
	}

	@Test
	void weeklyReportStaysSilentWithoutSnapshot() {
		properties.setBudgetSlackWebhook(WEBHOOK);
		when(stateStore.readLatest()).thenReturn(Optional.empty());

		service.sendWeeklyReport();

		verify(slackNotifier, never()).send(anyString(), anyString());
	}

	@Test
	void notifiesOnFirstThresholdCross() {
		activeStore(Optional.empty());

		service.handle(envelope("{\"costAmount\":5200.0,\"budgetAmount\":10000.0,"
				+ "\"currencyCode\":\"KRW\",\"costIntervalStart\":\"2026-07-01T00:00:00Z\","
				+ "\"alertThresholdExceeded\":0.5}"));

		verify(slackNotifier).send(eq(WEBHOOK), contains("50%"));
		verify(stateStore).save(new BudgetStateStore.State("2026-07-01T00:00:00Z", 50));
	}

	@Test
	void staysSilentWhenThresholdAlreadyNotified() {
		activeStore(Optional.of(new BudgetStateStore.State("2026-07-01T00:00:00Z", 50)));

		service.handle(envelope("{\"costAmount\":5300.0,\"budgetAmount\":10000.0,"
				+ "\"costIntervalStart\":\"2026-07-01T00:00:00Z\",\"alertThresholdExceeded\":0.5}"));

		verify(slackNotifier, never()).send(anyString(), anyString());
	}

	@Test
	void notifiesAgainForHigherThresholdAndNewMonth() {
		activeStore(Optional.of(new BudgetStateStore.State("2026-07-01T00:00:00Z", 50)));

		service.handle(envelope("{\"costAmount\":9100.0,\"budgetAmount\":10000.0,"
				+ "\"costIntervalStart\":\"2026-07-01T00:00:00Z\",\"alertThresholdExceeded\":0.9}"));
		service.handle(envelope("{\"costAmount\":5100.0,\"budgetAmount\":10000.0,"
				+ "\"costIntervalStart\":\"2026-08-01T00:00:00Z\",\"alertThresholdExceeded\":0.5}"));

		verify(slackNotifier).send(eq(WEBHOOK), contains("90%"));
		verify(slackNotifier).send(eq(WEBHOOK), contains("50%"));
	}

	@Test
	void staysSilentWithoutDedupStore() {
		properties.setBudgetSlackWebhook(WEBHOOK);
		when(stateStore.isActive()).thenReturn(false);

		service.handle(envelope("{\"costAmount\":5200.0,\"budgetAmount\":10000.0,"
				+ "\"costIntervalStart\":\"2026-07-01T00:00:00Z\",\"alertThresholdExceeded\":0.5}"));

		verify(slackNotifier, never()).send(anyString(), anyString());
	}

	@Test
	void survivesMalformedEnvelope() {
		service.handle(objectMapper.createObjectNode());

		verify(slackNotifier, never()).send(anyString(), anyString());
		verify(stateStore, never()).save(any());
	}
}
