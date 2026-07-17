package com.dashboard.subscription.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.dashboard.subscription.config.AlertProperties;

/**
 * One alert sweep: collect usage with the server-side (opt-in) credentials, evaluate thresholds,
 * and notify Slack. The last alert fingerprint is kept in memory so an unchanged alert state is
 * not re-sent every sweep; a restart (new instance) may repeat the latest alert once.
 */
@Service
public class AlertService {

	private final AlertProperties properties;
	private final CachedUsageService cachedUsageService;
	private final AlertEvaluator alertEvaluator;
	private final SlackNotifier slackNotifier;

	public AlertService(AlertProperties properties, CachedUsageService cachedUsageService,
			AlertEvaluator alertEvaluator, SlackNotifier slackNotifier) {
		this.properties = properties;
		this.cachedUsageService = cachedUsageService;
		this.alertEvaluator = alertEvaluator;
		this.slackNotifier = slackNotifier;
	}

	private volatile String lastFingerprint = "";

	public AlertCheckResult check() {
		if (!properties.isActive()) {
			return new AlertCheckResult(false, List.of(), false);
		}
		List<String> alerts = alertEvaluator.evaluate(
				cachedUsageService.collect(Map.of()).getProviders());
		String fingerprint = String.join("\n", alerts);
		boolean notified = false;
		if (!alerts.isEmpty() && !fingerprint.equals(lastFingerprint)) {
			slackNotifier.send(buildMessage(alerts));
			notified = true;
		}
		lastFingerprint = fingerprint;
		return new AlertCheckResult(true, alerts, notified);
	}

	private String buildMessage(List<String> alerts) {
		return "⚠️ 구독 서비스 잔여량 경고\n• " + String.join("\n• ", alerts);
	}
}
