package com.dashboard.subscription.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dashboard.subscription.config.AlertProperties;
import com.dashboard.subscription.domain.AlertSubscription;
import com.dashboard.subscription.domain.AlertThresholds;
import com.dashboard.subscription.domain.ProviderUsage;
import com.dashboard.subscription.service.SubscriptionStore.StoredSubscription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Multi-user alerting: subscribers register their own webhook, thresholds, and provider keys
 * (encrypted at rest, opt-in). Each sweep decrypts every subscription, collects usage with that
 * subscriber's keys, and notifies their webhook only when the alert state changed.
 */
@Service
public class AlertSubscriptionService {

	static final String SLACK_WEBHOOK_PREFIX = "https://hooks.slack.com/";

	private static final Logger log = LoggerFactory.getLogger(AlertSubscriptionService.class);

	private final AlertProperties properties;
	private final CryptoService cryptoService;
	private final SubscriptionStore subscriptionStore;
	private final CachedUsageService cachedUsageService;
	private final AlertEvaluator alertEvaluator;
	private final SlackNotifier slackNotifier;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public AlertSubscriptionService(AlertProperties properties, CryptoService cryptoService,
			SubscriptionStore subscriptionStore, CachedUsageService cachedUsageService,
			AlertEvaluator alertEvaluator, SlackNotifier slackNotifier) {
		this.properties = properties;
		this.cryptoService = cryptoService;
		this.subscriptionStore = subscriptionStore;
		this.cachedUsageService = cachedUsageService;
		this.alertEvaluator = alertEvaluator;
		this.slackNotifier = slackNotifier;
	}

	public boolean isActive() {
		return properties.isSubscriptionActive() && cryptoService.isActive();
	}

	public String subscribe(AlertSubscription subscription) {
		requireActive();
		validate(subscription);
		AlertSubscription normalized = new AlertSubscription(
				subscription.webhookUrl().trim(),
				subscription.keys(),
				resolveThresholds(subscription.thresholds()));
		return subscriptionStore.create(cryptoService.encrypt(toJson(normalized)));
	}

	public void unsubscribe(String subscriptionId) {
		requireActive();
		subscriptionStore.delete(subscriptionId);
	}

	public SweepResult sweep() {
		if (!isActive()) {
			return new SweepResult(false, 0, 0);
		}
		List<StoredSubscription> stored = subscriptionStore.list();
		int notified = 0;
		for (StoredSubscription entry : stored) {
			if (sweepOne(entry)) {
				notified++;
			}
		}
		return new SweepResult(true, stored.size(), notified);
	}

	/**
	 * Sweeps a single subscription; failures are isolated so one broken subscription cannot
	 * block the others. Returns whether a notification went out.
	 */
	private boolean sweepOne(StoredSubscription entry) {
		try {
			AlertSubscription subscription = objectMapper.readValue(
					cryptoService.decrypt(entry.encryptedPayload()), AlertSubscription.class);
			List<ProviderUsage> providers =
					cachedUsageService.collect(subscription.keys()).getProviders();
			List<String> alerts = alertEvaluator.evaluate(providers, subscription.thresholds());
			String fingerprint = String.join("\n", alerts);
			boolean changed = !fingerprint.equals(entry.lastFingerprint());
			boolean shouldNotify = !alerts.isEmpty() && changed;
			if (shouldNotify) {
				slackNotifier.send(subscription.webhookUrl(),
						"⚠️ 구독 서비스 잔여량 경고\n• " + String.join("\n• ", alerts));
			}
			if (changed) {
				subscriptionStore.updateFingerprint(entry.id(), fingerprint);
			}
			if (shouldNotify) {
				log.info("Alert notification sent for subscription {}", entry.id());
			}
			return shouldNotify;
		} catch (Exception exception) {
			// Message only — payloads (keys, webhooks) must never reach the logs.
			log.warn("Alert sweep failed for subscription {}: {}", entry.id(), exception.toString());
			return false;
		}
	}

	private void requireActive() {
		if (!isActive()) {
			throw new IllegalStateException("Alert subscriptions are not configured on this deployment");
		}
	}

	private void validate(AlertSubscription subscription) {
		if (subscription.webhookUrl() == null
				|| !subscription.webhookUrl().trim().startsWith(SLACK_WEBHOOK_PREFIX)) {
			throw new IllegalArgumentException(
					"webhookUrl must start with " + SLACK_WEBHOOK_PREFIX);
		}
		Map<String, ?> keys = subscription.keys();
		boolean hasAnyKey = keys != null && keys.values().stream().anyMatch(value -> value != null);
		if (!hasAnyKey) {
			throw new IllegalArgumentException("at least one provider key is required");
		}
	}

	private AlertThresholds resolveThresholds(AlertThresholds requested) {
		if (requested == null) {
			return new AlertThresholds(properties.getQuotaWarnPercent(),
					properties.getBalanceMinUsd(), properties.getCostMaxUsd());
		}
		return new AlertThresholds(
				requested.quotaWarnPercent() != null
						? requested.quotaWarnPercent() : properties.getQuotaWarnPercent(),
				requested.balanceMinUsd() != null
						? requested.balanceMinUsd() : properties.getBalanceMinUsd(),
				requested.costMaxUsd() != null ? requested.costMaxUsd() : properties.getCostMaxUsd());
	}

	private String toJson(AlertSubscription subscription) {
		try {
			return objectMapper.writeValueAsString(subscription);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize subscription", exception);
		}
	}
}
