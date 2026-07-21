package com.dashboard.subscription.service;

import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.AlertProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * Firestore-backed memory of the last budget threshold we notified about. Cloud Billing publishes
 * budget updates several times per hour once a threshold is exceeded, so without this state every
 * push would become a Slack message. One document, REST access like the other stores.
 */
@Slf4j
@Service
public class BudgetStateStore {

	private static final String DOCUMENT = "budgetState/current";

	private final AlertProperties properties;
	private final GcpTokenProvider tokenProvider;
	private final RestClient restClient;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public BudgetStateStore(AlertProperties properties, GcpTokenProvider tokenProvider,
			RestClient.Builder restClientBuilder) {
		this.properties = properties;
		this.tokenProvider = tokenProvider;
		this.restClient = restClientBuilder.build();
	}

	public record State(String intervalStart, int notifiedPercent) {
	}

	public boolean isActive() {
		return StringUtils.hasText(properties.getProjectId());
	}

	public Optional<State> read() {
		try {
			JsonNode document = restClient.get()
					.uri(documentUrl())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.accessToken())
					.retrieve()
					.body(JsonNode.class);
			JsonNode fields = document.path("fields");
			return Optional.of(new State(
					fields.path("intervalStart").path("stringValue").asText(""),
					(int) fields.path("notifiedPercent").path("integerValue").asLong(0)));
		} catch (Exception exception) {
			// First notification of the billing account has no document yet (404).
			return Optional.empty();
		}
	}

	public void save(State state) {
		try {
			ObjectNode document = objectMapper.createObjectNode();
			ObjectNode fields = document.putObject("fields");
			fields.putObject("intervalStart").put("stringValue", state.intervalStart());
			fields.putObject("notifiedPercent").put("integerValue", String.valueOf(state.notifiedPercent()));

			restClient.patch()
					.uri(documentUrl())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.accessToken())
					.contentType(MediaType.APPLICATION_JSON)
					.body(document.toString())
					.retrieve()
					.toBodilessEntity();
		} catch (Exception exception) {
			log.warn("Budget state write failed: {}", exception.toString());
		}
	}

	private String documentUrl() {
		return properties.getFirestoreBaseUrl() + "/v1/projects/" + properties.getProjectId()
				+ "/databases/(default)/documents/" + DOCUMENT;
	}
}
