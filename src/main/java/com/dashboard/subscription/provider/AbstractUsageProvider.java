package com.dashboard.subscription.provider;

import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.ProviderProperties;
import com.dashboard.subscription.config.ProviderProperties.Config;
import com.dashboard.subscription.domain.ApiCredentials;
import com.dashboard.subscription.domain.MetricType;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;

/**
 * Shared plumbing for providers: resolves per-provider config, builds a base-url-scoped {@link RestClient},
 * and turns the two non-OK outcomes (disabled, error) into normalized snapshots. Subclasses only implement
 * the happy-path call in {@link #fetchActive(Config)}.
 */
public abstract class AbstractUsageProvider implements UsageProvider {

	private final ProviderProperties properties;
	private final RestClient.Builder restClientBuilder;

	protected AbstractUsageProvider(ProviderProperties properties, RestClient.Builder restClientBuilder) {
		this.properties = properties;
		this.restClientBuilder = restClientBuilder;
	}

	protected abstract String displayName();

	protected abstract MetricType metricType();

	protected abstract ProviderUsage fetchActive(Config config);

	@Override
	public ProviderUsage fetch() {
		return fetch(ApiCredentials.EMPTY);
	}

	@Override
	public ProviderUsage fetch(ApiCredentials credentials) {
		Config config = effectiveConfig(credentials);
		ProviderUsage usage;
		if (!config.isActive()) {
			usage = disabled();
		} else {
			try {
				usage = fetchActive(config);
			} catch (Exception exception) {
				usage = error(exception);
			}
		}
		return usage.toBuilder()
				.monthlyFee(config.getMonthlyFee())
				.build();
	}

	/**
	 * Overlays per-request BYOK credentials on the server-side config. Request values win field by
	 * field; the merged copy lives only for this request and is never stored.
	 */
	private Config effectiveConfig(ApiCredentials credentials) {
		Config config = properties.require(providerId());
		if (credentials == null
				|| (!StringUtils.hasText(credentials.apiKey()) && !StringUtils.hasText(credentials.teamId()))) {
			return config;
		}
		Config merged = new Config();
		merged.setEnabled(config.isEnabled());
		merged.setBaseUrl(config.getBaseUrl());
		merged.setMonthlyFee(config.getMonthlyFee());
		merged.setApiKey(StringUtils.hasText(credentials.apiKey()) ? credentials.apiKey() : config.getApiKey());
		merged.setTeamId(StringUtils.hasText(credentials.teamId()) ? credentials.teamId() : config.getTeamId());
		return merged;
	}

	protected RestClient client(Config config) {
		String baseUrl = config.getBaseUrl();
		if (baseUrl != null && baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}
		return restClientBuilder.clone()
				.baseUrl(baseUrl)
				.build();
	}

	protected ProviderUsage.ProviderUsageBuilder base() {
		return ProviderUsage.builder()
				.providerId(providerId())
				.displayName(displayName())
				.metricType(metricType());
	}

	protected ProviderUsage disabled() {
		return base()
				.status(ProviderStatus.DISABLED)
				.message("No credential configured")
				.build();
	}

	protected ProviderUsage error(Exception exception) {
		String reason = exception.getMessage();
		if (reason == null || reason.isBlank()) {
			reason = exception.getClass().getSimpleName();
		}
		return base()
				.status(ProviderStatus.ERROR)
				.message(reason)
				.build();
	}
}
