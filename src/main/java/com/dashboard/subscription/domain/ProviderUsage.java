package com.dashboard.subscription.domain;

import java.time.Instant;

import lombok.Builder;
import lombok.Getter;

/**
 * Normalized snapshot for one provider, shaped so the dashboard can render every provider the same way
 * regardless of which metric type it reports.
 */
@Getter
@Builder(toBuilder = true)
public class ProviderUsage {

	private final String providerId;
	private final String displayName;
	private final MetricType metricType;
	private final ProviderStatus status;

	private final String plan;
	private final String unit;

	private final Double used;
	private final Double limit;
	private final Double remaining;

	private final Double cost;
	private final String currency;

	private final Instant resetsAt;
	private final String message;

	/** Fixed monthly subscription fee in KRW, sourced from configuration; null when not configured. */
	private final Long monthlyFee;

	/**
	 * Portion of the allowance already consumed, in the range 0..100, or {@code null} when a percentage
	 * is not meaningful (balance or cost metrics, or an unknown limit).
	 */
	public Double getUsedPercent() {
		if (metricType != MetricType.QUOTA) {
			return null;
		}
		if (used == null || limit == null || limit <= 0.0d) {
			return null;
		}
		double percent = (used / limit) * 100.0d;
		if (percent < 0.0d) {
			return 0.0d;
		}
		if (percent > 100.0d) {
			return 100.0d;
		}
		return percent;
	}
}
