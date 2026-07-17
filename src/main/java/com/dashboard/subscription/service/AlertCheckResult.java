package com.dashboard.subscription.service;

import java.util.List;

/**
 * Outcome of one alert sweep: whether alerting is configured at all, the alert lines found,
 * and whether a Slack notification actually went out this sweep.
 */
public record AlertCheckResult(boolean enabled, List<String> alerts, boolean notified) {
}
