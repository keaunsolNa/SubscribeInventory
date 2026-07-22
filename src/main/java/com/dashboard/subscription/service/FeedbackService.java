package com.dashboard.subscription.service;

import java.time.Clock;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.AlertProperties;
import com.dashboard.subscription.domain.AuthUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * User feedback intake: every submission is stored in Firestore (when configured) and relayed to
 * the operator's Slack webhook (when configured), both best-effort so a storage hiccup never
 * shows the user an error. Abuse is bounded by the auth gate on /api/** plus the length cap.
 */
@Slf4j
@Service
public class FeedbackService {

	private static final int MAX_LENGTH = 2000;
	private static final String COLLECTION = "feedback";
	private static final String WEBHOOK_PREFIX = "https://hooks.slack.com/";

	private final AlertProperties properties;
	private final SlackNotifier slackNotifier;
	private final GcpTokenProvider tokenProvider;
	private final RestClient restClient;
	private final Clock clock;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public FeedbackService(AlertProperties properties, SlackNotifier slackNotifier,
			GcpTokenProvider tokenProvider, RestClient.Builder restClientBuilder, Clock clock) {
		this.properties = properties;
		this.slackNotifier = slackNotifier;
		this.tokenProvider = tokenProvider;
		this.restClient = restClientBuilder.build();
		this.clock = clock;
	}

	public void submit(String message, AuthUser user) {
		if (message == null || message.isBlank()) {
			throw new IllegalArgumentException("내용을 입력해 주세요");
		}
		if (message.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("내용이 너무 깁니다 (최대 " + MAX_LENGTH + "자)");
		}
		String email = user != null ? user.email() : "(비로그인)";
		store(email, message);
		relayToSlack(email, message);
		log.info("Feedback received from {} ({} chars)", email, message.length());
	}

	private void store(String email, String message) {
		if (!StringUtils.hasText(properties.getProjectId())) {
			return;
		}
		try {
			ObjectNode document = objectMapper.createObjectNode();
			ObjectNode fields = document.putObject("fields");
			fields.putObject("email").put("stringValue", email);
			fields.putObject("message").put("stringValue", message);
			fields.putObject("createdAt").put("stringValue", clock.instant().toString());

			restClient.post()
					.uri(properties.getFirestoreBaseUrl() + "/v1/projects/"
							+ properties.getProjectId() + "/databases/(default)/documents/"
							+ COLLECTION)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.accessToken())
					.contentType(MediaType.APPLICATION_JSON)
					.body(document.toString())
					.retrieve()
					.toBodilessEntity();
		} catch (Exception exception) {
			log.warn("Feedback store failed: {}", exception.toString());
		}
	}

	private void relayToSlack(String email, String message) {
		String webhook = properties.getFeedbackSlackWebhook();
		if (!StringUtils.hasText(webhook) || !webhook.startsWith(WEBHOOK_PREFIX)) {
			return;
		}
		try {
			slackNotifier.send(webhook, "💬 피드백 — " + email + "\n" + message);
		} catch (Exception exception) {
			log.warn("Feedback Slack relay failed: {}", exception.toString());
		}
	}
}
