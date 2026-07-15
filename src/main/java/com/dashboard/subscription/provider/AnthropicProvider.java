package com.dashboard.subscription.provider;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.ProviderProperties;
import com.dashboard.subscription.config.ProviderProperties.Config;
import com.dashboard.subscription.domain.MetricType;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Anthropic (Claude) month-to-date spend via the Admin Cost Report
 * GET /v1/organizations/cost_report?starting_at={rfc3339} (headers x-api-key admin key, anthropic-version).
 * Anthropic billing is postpaid, so this reports accumulated cost, not a remaining quota.
 * Response buckets look like: data[].results[].amount (decimal string) with results[].currency.
 */
@Component
public class AnthropicProvider extends AbstractUsageProvider {

	static final String PROVIDER_ID = "anthropic";
	private static final String COST_REPORT_PATH = "/v1/organizations/cost_report";
	private static final String ANTHROPIC_VERSION = "2023-06-01";

	private final Clock clock;

	public AnthropicProvider(ProviderProperties properties, RestClient.Builder restClientBuilder, Clock clock) {
		super(properties, restClientBuilder);
		this.clock = clock;
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	@Override
	protected String displayName() {
		return "Anthropic (Claude)";
	}

	@Override
	protected MetricType metricType() {
		return MetricType.COST;
	}

	@Override
	protected ProviderUsage fetchActive(Config config) {
		String startingAt = firstOfMonthRfc3339();
		JsonNode body = client(config).get()
				.uri(builder -> builder.path(COST_REPORT_PATH)
						.queryParam("starting_at", startingAt)
						.build())
				.header("x-api-key", config.getApiKey())
				.header("anthropic-version", ANTHROPIC_VERSION)
				.retrieve()
				.body(JsonNode.class);
		return parse(body);
	}

	ProviderUsage parse(JsonNode body) {
		double total = 0.0d;
		String currency = "USD";
		for (JsonNode bucket : body.path("data")) {
			for (JsonNode result : bucket.path("results")) {
				total += result.path("amount").asDouble();
				if (result.hasNonNull("currency")) {
					currency = result.path("currency").asText(currency);
				}
			}
		}

		return base()
				.status(ProviderStatus.OK)
				.plan("Usage-based")
				.unit(currency)
				.cost(total)
				.currency(currency)
				.build();
	}

	private String firstOfMonthRfc3339() {
		LocalDate today = LocalDate.now(clock);
		return today.withDayOfMonth(1)
				.atStartOfDay(ZoneOffset.UTC)
				.format(DateTimeFormatter.ISO_INSTANT);
	}
}
