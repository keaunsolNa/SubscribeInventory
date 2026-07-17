package com.dashboard.subscription.service;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.AlertProperties;

/**
 * Posts alert text to the configured Slack Incoming Webhook. The webhook URL is a secret;
 * it is read from configuration and never logged.
 */
@Service
public class SlackNotifier {

	private final AlertProperties properties;
	private final RestClient restClient;

	public SlackNotifier(AlertProperties properties, RestClient.Builder restClientBuilder) {
		this.properties = properties;
		this.restClient = restClientBuilder.build();
	}

	public void send(String text) {
		restClient.post()
				.uri(properties.getWebhookUrl())
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("text", text))
				.retrieve()
				.toBodilessEntity();
	}
}
