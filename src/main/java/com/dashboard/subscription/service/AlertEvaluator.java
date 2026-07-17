package com.dashboard.subscription.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.dashboard.subscription.config.AlertProperties;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;

/**
 * Turns provider snapshots into human-readable alert lines based on the configured thresholds.
 * Only OK snapshots are considered; DISABLED and ERROR cards never alert.
 */
@Service
public class AlertEvaluator {

	private final AlertProperties properties;

	public AlertEvaluator(AlertProperties properties) {
		this.properties = properties;
	}

	public List<String> evaluate(List<ProviderUsage> providers) {
		List<String> alerts = new ArrayList<>();
		for (ProviderUsage usage : providers) {
			if (usage.getStatus() != ProviderStatus.OK) {
				continue;
			}
			switch (usage.getMetricType()) {
				case QUOTA -> evaluateQuota(usage, alerts);
				case BALANCE -> evaluateBalance(usage, alerts);
				case COST -> evaluateCost(usage, alerts);
			}
		}
		return alerts;
	}

	private void evaluateQuota(ProviderUsage usage, List<String> alerts) {
		Double usedPercent = usage.getUsedPercent();
		if (usedPercent != null && usedPercent >= properties.getQuotaWarnPercent()) {
			alerts.add(String.format("%s: 사용량 %.1f%% (임계 %.0f%%)",
					usage.getDisplayName(), usedPercent, properties.getQuotaWarnPercent()));
		}
	}

	private void evaluateBalance(ProviderUsage usage, List<String> alerts) {
		Double remaining = usage.getRemaining();
		if (remaining != null && remaining <= properties.getBalanceMinUsd()) {
			alerts.add(String.format("%s: 잔여 크레딧 $%.2f (기준 $%.2f)",
					usage.getDisplayName(), remaining, properties.getBalanceMinUsd()));
		}
	}

	private void evaluateCost(ProviderUsage usage, List<String> alerts) {
		Double costMax = properties.getCostMaxUsd();
		Double cost = usage.getCost();
		if (costMax != null && cost != null && cost >= costMax) {
			alerts.add(String.format("%s: 이번 달 사용액 $%.2f (한도 $%.2f)",
					usage.getDisplayName(), cost, costMax));
		}
	}
}
