package com.dashboard.subscription.provider;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.ProviderProperties;
import com.dashboard.subscription.config.ProviderProperties.Config;
import com.dashboard.subscription.domain.MetricType;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * OpenAI month-to-date spend via the Costs endpoint
 * GET /v1/organization/costs?start_time={epoch}&limit=31 (admin bearer key).
 * OpenAI exposes no remaining-balance endpoint, so this reports accumulated cost, not a quota.
 * Response buckets look like: data[].results[].amount.value (currency in amount.currency).
 */
@Component
public class OpenAiProvider extends AbstractUsageProvider {

	static final String PROVIDER_ID = "openai";
	private static final String COSTS_PATH = "/v1/organization/costs";
	private static final int BUCKET_LIMIT = 31;

	private final Clock clock;

	public OpenAiProvider(ProviderProperties properties, RestClient.Builder restClientBuilder, Clock clock) {
		super(properties, restClientBuilder);
		this.clock = clock;
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	@Override
	protected String displayName() {
		return "OpenAI";
	}

	@Override
	protected MetricType metricType() {
		return MetricType.COST;
	}

	@Override
	protected ProviderUsage fetchActive(Config config) {
		long startOfMonth = firstOfMonthEpochSeconds();
		JsonNode body = client(config).get()
				.uri(builder -> builder.path(COSTS_PATH)
						.queryParam("start_time", startOfMonth)
						.queryParam("limit", BUCKET_LIMIT)
						.build())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
				.retrieve()
				.body(JsonNode.class);
		return parse(body);
	}

	ProviderUsage parse(JsonNode body) {
		double total = 0.0d;
		String currency = "usd";
		for (JsonNode bucket : body.path("data")) {
			for (JsonNode result : bucket.path("results")) {
				JsonNode amount = result.path("amount");
				total += amount.path("value").asDouble();
				if (amount.hasNonNull("currency")) {
					currency = amount.path("currency").asText(currency);
				}
			}
		}

		return base()
				.status(ProviderStatus.OK)
				.plan("Pay-as-you-go")
				.unit(currency.toUpperCase())
				.cost(total)
				.currency(currency.toUpperCase())
				.build();
	}

	private long firstOfMonthEpochSeconds() {
		LocalDate today = LocalDate.now(clock);
		return today.withDayOfMonth(1)
				.atStartOfDay(ZoneOffset.UTC)
				.toEpochSecond();
	}
}
