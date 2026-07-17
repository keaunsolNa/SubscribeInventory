package com.dashboard.subscription.domain;

import java.util.Map;

/**
 * One user's alert subscription: their Slack webhook, the provider credentials they opted in to
 * store (encrypted at rest), and their thresholds. Serialized to JSON and encrypted as a whole
 * before storage.
 */
public record AlertSubscription(String webhookUrl, Map<String, ApiCredentials> keys,
		AlertThresholds thresholds) {
}
