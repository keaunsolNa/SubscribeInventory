package com.dashboard.subscription.domain;

/**
 * Per-subscription alert thresholds. Null quota/balance fall back to server defaults when the
 * subscription is created; a null costMaxUsd keeps cost alerting off for that subscriber.
 */
public record AlertThresholds(Double quotaWarnPercent, Double balanceMinUsd, Double costMaxUsd) {
}
