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

	private String webhookUrl;
	private double quotaWarnPercent = 80.0d;
	private double balanceMinUsd = 5.0d;
	private Double costMaxUsd;

	public boolean isActive() {
		return StringUtils.hasText(webhookUrl);
	}
}
