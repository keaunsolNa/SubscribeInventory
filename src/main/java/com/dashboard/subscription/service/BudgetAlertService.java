package com.dashboard.subscription.service;

import java.time.Clock;
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
	private final Clock clock;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public BudgetAlertService(AlertProperties properties, SlackNotifier slackNotifier,
			BudgetStateStore stateStore, Clock clock) {
		this.properties = properties;
		this.slackNotifier = slackNotifier;
		this.stateStore = stateStore;
		this.clock = clock;
	}

	/** Handles one Pub/Sub push envelope; never throws so pushes are always acked. */
	public void handle(JsonNode envelope) {
		try {
			String data = envelope.path("message").path("data").asText("");
			if (data.isEmpty()) {
				return;
			}
			JsonNode budget = objectMapper.readTree(Base64.getDecoder().decode(data));
			if (stateStore.isActive()) {
				stateStore.saveLatest(new BudgetStateStore.LatestSpend(
						budget.path("costAmount").asDouble(),
						budget.path("budgetAmount").asDouble(),
						budget.path("currencyCode").asText("KRW"),
						clock.instant().toString()));
			}
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

	/** Weekly spend digest (Cloud Scheduler, Monday mornings); silent without data or webhook. */
	public void sendWeeklyReport() {
		String webhook = properties.getBudgetSlackWebhook();
		if (!StringUtils.hasText(webhook) || !webhook.startsWith(WEBHOOK_PREFIX)) {
			log.info("Weekly budget report skipped: no Slack webhook configured");
			return;
		}
		Optional<BudgetStateStore.LatestSpend> latest = stateStore.readLatest();
		if (latest.isEmpty()) {
			log.info("Weekly budget report skipped: no spend snapshot yet");
			return;
		}
		BudgetStateStore.LatestSpend spend = latest.get();
		double percent = spend.budget() > 0 ? spend.cost() / spend.budget() * 100 : 0;
		slackNotifier.send(webhook, String.format(
				"📊 주간 GCP 사용액 리포트%n이번 달 현재 %,.0f %s / 예산 %,.0f %s (%.1f%%)",
				spend.cost(), spend.currency(), spend.budget(), spend.currency(), percent));
		log.info("Weekly budget report sent");
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
