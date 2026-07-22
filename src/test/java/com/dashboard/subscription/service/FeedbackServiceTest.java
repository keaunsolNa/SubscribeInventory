package com.dashboard.subscription.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.AlertProperties;
import com.dashboard.subscription.domain.AuthUser;

class FeedbackServiceTest {

	private static final String WEBHOOK = "https://hooks.slack.com/services/T/B/x";

	private final AlertProperties properties = new AlertProperties();
	private final SlackNotifier slackNotifier = mock(SlackNotifier.class);
	private final FeedbackService service = new FeedbackService(properties, slackNotifier,
			mock(GcpTokenProvider.class), RestClient.builder(),
			Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC));

	@Test
	void rejectsBlankAndOversizedMessages() {
		assertThatThrownBy(() -> service.submit("  ", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.submit("a".repeat(2001), null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void relaysToSlackWithSenderEmail() {
		properties.setFeedbackSlackWebhook(WEBHOOK);

		service.submit("다크 모드가 좋아요", new AuthUser("sub-1", "user@example.com"));

		verify(slackNotifier).send(eq(WEBHOOK), contains("user@example.com"));
		verify(slackNotifier).send(eq(WEBHOOK), contains("다크 모드가 좋아요"));
	}

	@Test
	void staysSilentWithoutWebhook() {
		service.submit("피드백", null);

		verify(slackNotifier, never()).send(anyString(), anyString());
	}
}
