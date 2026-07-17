package com.dashboard.subscription.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import lombok.Getter;
import lombok.Setter;

/**
 * Binds {@code dashboard.alerts.*}. Alerting is active only when a Slack webhook URL is present;
 * thresholds fall back to the defaults below. Cost alerting stays off until a max is configured.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "dashboard.alerts")
public class AlertProperties {

	private double quotaWarnPercent = 80.0d;
	private double balanceMinUsd = 5.0d;
	private Double costMaxUsd;

	/** Base64-encoded 32-byte AES key for subscription payloads; blank disables subscriptions. */
	private String encryptionKey;
	/** GCP project holding the Firestore subscription store; blank disables subscriptions. */
	private String projectId;
	private String firestoreBaseUrl = "https://firestore.googleapis.com";

	/** Alert subscriptions require both the encryption key and a Firestore project. */
	public boolean isSubscriptionActive() {
		return StringUtils.hasText(encryptionKey) && StringUtils.hasText(projectId);
	}
}
