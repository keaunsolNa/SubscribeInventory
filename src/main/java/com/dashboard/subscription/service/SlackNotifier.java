package com.dashboard.subscription.service;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Posts alert text to a Slack Incoming Webhook. URL validation happens once at subscribe time
 * ({@link AlertSubscriptionService}); stored payloads are encrypted, so only URLs that passed
 * that gate ever reach this sender. Webhook URLs are never logged.
 */
@Service
public class SlackNotifier {

	private final RestClient restClient;

	public SlackNotifier(RestClient.Builder restClientBuilder) {
		this.restClient = restClientBuilder.build();
	}

	public void send(String webhookUrl, String text) {
		restClient.post()
				.uri(webhookUrl)
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("text", text))
				.retrieve()
				.toBodilessEntity();
	}
}
