package com.dashboard.subscription.service;

import java.util.Base64;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.dashboard.subscription.config.AlertProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Relays Cloud Billing budget notifications (budget → Pub/Sub push → this service) to Slack.
 * Budgets can only email directly, so this is the supported path to a Slack channel. Cloud
 * Billing re-publishes the current spend continuously, so a message goes out only when a higher
 * alert threshold is crossed (or a new billing month starts), tracked via {@link BudgetStateStore}.
 */
@Slf4j
@Service
public class BudgetAlertService {

	private static final String WEBHOOK_PREFIX = "https://hooks.slack.com/";

	private final AlertProperties properties;
	private final SlackNotifier slackNotifier;
	private final BudgetStateStore stateStore;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public BudgetAlertService(AlertProperties properties, SlackNotifier slackNotifier,
			BudgetStateStore stateStore) {
		this.properties = properties;
		this.slackNotifier = slackNotifier;
		this.stateStore = stateStore;
	}

	/** Handles one Pub/Sub push envelope; never throws so pushes are always acked. */
	public void handle(JsonNode envelope) {
		try {
			String data = envelope.path("message").path("data").asText("");
			if (data.isEmpty()) {
				return;
			}
			JsonNode budget = objectMapper.readTree(Base64.getDecoder().decode(data));
			JsonNode exceeded = budget.path("alertThresholdExceeded");
			if (exceeded.isMissingNode() || exceeded.isNull()) {
				return;
			}
			int percent = (int) Math.round(exceeded.asDouble() * 100);
			String interval = budget.path("costIntervalStart").asText("");
			if (!shouldNotify(interval, percent)) {
				return;
			}
			String webhook = properties.getBudgetSlackWebhook();
			if (!StringUtils.hasText(webhook) || !webhook.startsWith(WEBHOOK_PREFIX)) {
				log.info("Budget threshold {}% crossed but no Slack webhook configured", percent);
				return;
			}
			slackNotifier.send(webhook, String.format(
					"💸 GCP 예산 경고: 이번 달 사용액 %,.0f %s — 예산 %,.0f %s의 %d%% 도달",
					budget.path("costAmount").asDouble(), budget.path("currencyCode").asText("KRW"),
					budget.path("budgetAmount").asDouble(), budget.path("currencyCode").asText("KRW"),
					percent));
			stateStore.save(new BudgetStateStore.State(interval, percent));
			log.info("Budget Slack notification sent: {}%", percent);
		} catch (Exception exception) {
			// Ack anyway: a malformed or transiently failing message must not retry forever.
			log.warn("Budget notification handling failed: {}", exception.toString());
		}
	}

	private boolean shouldNotify(String interval, int percent) {
		if (!stateStore.isActive()) {
			// Without the dedup store every re-publish would spam the channel; stay silent.
			return false;
		}
		Optional<BudgetStateStore.State> state = stateStore.read();
		if (state.isEmpty() || !state.get().intervalStart().equals(interval)) {
			return true;
		}
		return percent > state.get().notifiedPercent();
	}
}
