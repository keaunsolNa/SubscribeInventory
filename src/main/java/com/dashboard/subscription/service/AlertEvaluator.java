package com.dashboard.subscription.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.dashboard.subscription.domain.AlertThresholds;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;

/**
 * Turns provider snapshots into human-readable alert lines based on the configured thresholds.
 * Only OK snapshots are considered; DISABLED and ERROR cards never alert.
 */
@Service
public class AlertEvaluator {

	public List<String> evaluate(List<ProviderUsage> providers, AlertThresholds thresholds) {
		List<String> alerts = new ArrayList<>();
		for (ProviderUsage usage : providers) {
			if (usage.getStatus() != ProviderStatus.OK) {
				continue;
			}
			switch (usage.getMetricType()) {
				case QUOTA -> evaluateQuota(usage, thresholds, alerts);
				case BALANCE -> evaluateBalance(usage, thresholds, alerts);
				case COST -> evaluateCost(usage, thresholds, alerts);
			}
		}
		return alerts;
	}

	private void evaluateQuota(ProviderUsage usage, AlertThresholds thresholds, List<String> alerts) {
		Double usedPercent = usage.getUsedPercent();
		Double warnPercent = thresholds.quotaWarnPercent();
		if (usedPercent != null && warnPercent != null && usedPercent >= warnPercent) {
			alerts.add(String.format("%s: 사용량 %.1f%% (임계 %.0f%%)",
					usage.getDisplayName(), usedPercent, warnPercent));
		}
	}

	private void evaluateBalance(ProviderUsage usage, AlertThresholds thresholds, List<String> alerts) {
		Double remaining = usage.getRemaining();
		Double minUsd = thresholds.balanceMinUsd();
		if (remaining != null && minUsd != null && remaining <= minUsd) {
			alerts.add(String.format("%s: 잔여 크레딧 $%.2f (기준 $%.2f)",
					usage.getDisplayName(), remaining, minUsd));
		}
	}

	private void evaluateCost(ProviderUsage usage, AlertThresholds thresholds, List<String> alerts) {
		Double costMax = thresholds.costMaxUsd();
		Double cost = usage.getCost();
		if (costMax != null && cost != null && cost >= costMax) {
			alerts.add(String.format("%s: 이번 달 사용액 $%.2f (한도 $%.2f)",
					usage.getDisplayName(), cost, costMax));
		}
	}
}
