package com.dashboard.subscription.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import lombok.Getter;
import lombok.Setter;

/**
 * Binds {@code dashboard.providers.*} from configuration. Each provider is keyed by its id
 * (elevenlabs, xai, openai, anthropic).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "dashboard")
public class ProviderProperties {

	private Map<String, Config> providers = new HashMap<>();

	/**
	 * Returns the config for a provider id, or an empty disabled config when none is bound,
	 * so callers never dereference null.
	 */
	public Config require(String providerId) {
		return providers.getOrDefault(providerId, new Config());
	}

	@Getter
	@Setter
	public static class Config {

		private boolean enabled = true;
		private String baseUrl;
		private String apiKey;
		private String teamId;
		private Long monthlyFee;

		/**
		 * A provider is active only when it is enabled and carries a non-blank api key.
		 */
		public boolean isActive() {
			return enabled && StringUtils.hasText(apiKey);
		}
	}
}
