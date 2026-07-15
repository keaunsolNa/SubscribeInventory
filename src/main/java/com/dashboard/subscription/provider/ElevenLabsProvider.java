package com.dashboard.subscription.provider;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.ProviderProperties;
import com.dashboard.subscription.config.ProviderProperties.Config;
import com.dashboard.subscription.domain.MetricType;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * ElevenLabs quota via GET /v1/user/subscription (auth header xi-api-key).
 * Reports remaining synthesis characters for the current billing period.
 */
@Component
public class ElevenLabsProvider extends AbstractUsageProvider {

	static final String PROVIDER_ID = "elevenlabs";
	private static final String SUBSCRIPTION_PATH = "/v1/user/subscription";

	public ElevenLabsProvider(ProviderProperties properties, RestClient.Builder restClientBuilder) {
		super(properties, restClientBuilder);
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	@Override
	protected String displayName() {
		return "ElevenLabs";
	}

	@Override
	protected MetricType metricType() {
		return MetricType.QUOTA;
	}

	@Override
	protected ProviderUsage fetchActive(Config config) {
		JsonNode body = client(config).get()
				.uri(SUBSCRIPTION_PATH)
				.header("xi-api-key", config.getApiKey())
				.retrieve()
				.body(JsonNode.class);
		return parse(body);
	}

	ProviderUsage parse(JsonNode body) {
		double used = body.path("character_count").asDouble();
		double limit = body.path("character_limit").asDouble();
		long resetUnix = body.path("next_character_count_reset_unix").asLong();
		Instant resetsAt = resetUnix > 0L ? Instant.ofEpochSecond(resetUnix) : null;

		return base()
				.status(ProviderStatus.OK)
				.plan(body.path("tier").asText(null))
				.unit("characters")
				.used(used)
				.limit(limit)
				.remaining(Math.max(0.0d, limit - used))
				.currency(body.path("currency").asText(null))
				.resetsAt(resetsAt)
				.build();
	}
}
