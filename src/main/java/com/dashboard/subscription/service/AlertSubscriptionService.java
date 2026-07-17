package com.dashboard.subscription.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

	static final int MAX_SUBSCRIPTIONS = 100;
	static final int MAX_SUBSCRIPTIONS_PER_USER = 3;

	public String subscribe(AlertSubscription subscription, String ownerId) {
		requireActive();
		validate(subscription);
		String owner = ownerId == null ? "" : ownerId;
		String webhookUrl = subscription.webhookUrl().trim();
		String hash = webhookHash(webhookUrl);

		List<StoredSubscription> existing = subscriptionStore.list();
		List<StoredSubscription> replaced = existing.stream()
				.filter(entry -> hash.equals(entry.webhookHash()) && owner.equals(entry.ownerId()))
				.toList();
		if (existing.size() - replaced.size() >= MAX_SUBSCRIPTIONS) {
			throw new IllegalArgumentException("subscription limit reached");
		}
		if (!owner.isEmpty()) {
			long ownedAfterReplace = existing.stream()
					.filter(entry -> owner.equals(entry.ownerId()))
					.count() - replaced.size();
			if (ownedAfterReplace >= MAX_SUBSCRIPTIONS_PER_USER) {
				throw new IllegalArgumentException("per-user subscription limit reached");
			}
		}

		sendConfirmation(webhookUrl);
		AlertSubscription normalized =
				new AlertSubscription(webhookUrl, subscription.keys(),
						resolveThresholds(subscription.thresholds()));
		String id = subscriptionStore.create(cryptoService.encrypt(toJson(normalized)), hash, owner);
		// Same webhook resubscribed = settings update: drop the previous entries after the new
		// one exists so there is never a gap without a subscription.
		replaced.forEach(entry -> subscriptionStore.delete(entry.id()));
		return id;
	}

	/**
	 * Delivers a confirmation message so a broken webhook is rejected at subscribe time instead
	 * of failing silently on every sweep.
	 */
	private void sendConfirmation(String webhookUrl) {
		try {
			slackNotifier.send(webhookUrl,
					"🔔 구독 서비스 대시보드 알림 구독이 등록되었습니다. 임계값 도달 시 이 채널로 알림이 전송됩니다.");
		} catch (Exception exception) {
			throw new IllegalArgumentException(
					"webhook delivery failed — check the webhook URL", exception);
		}
	}

	static String webhookHash(String webhookUrl) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(
					digest.digest(webhookUrl.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	/**
	 * Deletes the subscription. When an authenticated owner is present, only their own
	 * subscription may be removed; legacy (ownerless) mode keeps the old direct delete.
	 */
	public void unsubscribe(String subscriptionId, String ownerId) {
		requireActive();
		String owner = ownerId == null ? "" : ownerId;
		if (owner.isEmpty()) {
			subscriptionStore.delete(subscriptionId);
			return;
		}
		boolean owned = subscriptionStore.list().stream()
				.anyMatch(entry -> entry.id().equals(subscriptionId)
						&& owner.equals(entry.ownerId()));
		if (!owned) {
			throw new IllegalArgumentException("subscription not found");
		}
		subscriptionStore.delete(subscriptionId);
	}

	public SweepResult sweep() {
		if (!isActive()) {
			return new SweepResult(false, 0, 0);
		}
		List<StoredSubscription> stored = subscriptionStore.list();
		// Parallel so one slow upstream cannot stretch the sweep past the request timeout.
		long notified = stored.parallelStream()
				.filter(this::sweepOne)
				.count();
		return new SweepResult(true, stored.size(), (int) notified);
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
			log.info("Sweep subscription {}: providers=[{}], thresholds={}, alerts={}, changed={}",
					entry.id(), statusSummary(providers), subscription.thresholds(), alerts.size(),
					changed);
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

	private String statusSummary(List<ProviderUsage> providers) {
		StringBuilder summary = new StringBuilder();
		for (ProviderUsage usage : providers) {
			if (summary.length() > 0) {
				summary.append(", ");
			}
			summary.append(usage.getProviderId()).append('=').append(usage.getStatus());
		}
		return summary.toString();
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
