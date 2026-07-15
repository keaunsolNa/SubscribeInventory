package com.dashboard.subscription.provider;

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
 * xAI (Grok) prepaid credit via the Management API
 * GET /v1/billing/teams/{teamId}/postpaid/invoice/preview (bearer auth).
 * Monetary values arrive as objects holding a {@code val} string in USD cents where
 * purchases and spend are both negative; remaining = |prepaidCredits| - |prepaidCreditsUsed|.
 * (The prepaid/balance endpoint only ledgers top-ups, so spend never shows up there.)
 */
@Component
public class XaiProvider extends AbstractUsageProvider {

	static final String PROVIDER_ID = "xai";
	private static final String INVOICE_PREVIEW_PATH = "/v1/billing/teams/{teamId}/postpaid/invoice/preview";
	private static final double CENTS_PER_DOLLAR = 100.0d;

	public XaiProvider(ProviderProperties properties, RestClient.Builder restClientBuilder) {
		super(properties, restClientBuilder);
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	@Override
	protected String displayName() {
		return "xAI (Grok)";
	}

	@Override
	protected MetricType metricType() {
		return MetricType.BALANCE;
	}

	@Override
	protected ProviderUsage fetchActive(Config config) {
		JsonNode body = client(config).get()
				.uri(INVOICE_PREVIEW_PATH, config.getTeamId())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
				.retrieve()
				.body(JsonNode.class);
		return parse(body);
	}

	ProviderUsage parse(JsonNode body) {
		JsonNode invoice = body.path("coreInvoice");
		double creditDollars = centsToDollars(invoice.path("prepaidCredits"));
		double usedDollars = centsToDollars(invoice.path("prepaidCreditsUsed"));

		return base()
				.status(ProviderStatus.OK)
				.plan("Prepaid")
				.unit("USD")
				.limit(creditDollars)
				.used(usedDollars)
				.remaining(creditDollars - usedDollars)
				.currency("USD")
				.build();
	}

	private double centsToDollars(JsonNode money) {
		return Math.abs(money.path("val").asDouble()) / CENTS_PER_DOLLAR;
	}
}
