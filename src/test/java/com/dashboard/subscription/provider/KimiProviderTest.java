package com.dashboard.subscription.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.ProviderProperties;
import com.dashboard.subscription.config.ProviderProperties.Config;
import com.dashboard.subscription.domain.MetricType;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class KimiProviderTest {

	private MockWebServer server;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	void fetchReadsAvailableBalance() throws InterruptedException {
		String json = """
				{
				  "code": 0,
				  "data": {
				    "available_balance": 49.58894,
				    "voucher_balance": 46.58893,
				    "cash_balance": 3.00001
				  },
				  "scode": "0x0",
				  "status": true
				}
				""";
		server.enqueue(new MockResponse().setBody(json)
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("sk-kimi").fetch();

		assertThat(usage.getProviderId()).isEqualTo("kimi");
		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getMetricType()).isEqualTo(MetricType.BALANCE);
		assertThat(usage.getRemaining()).isEqualTo(49.58894d);
		assertThat(usage.getCurrency()).isEqualTo("USD");

		RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
		assertThat(recorded.getPath()).isEqualTo("/v1/users/me/balance");
		assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-kimi");
	}

	/**
	 * cash_balance can go negative into debt while vouchers still cover calls, so reporting it —
	 * or summing the two — would show a negative or inflated figure for an account that still works.
	 * available_balance is the one Moonshot gates on.
	 */
	@Test
	void reportsSpendablePortionRatherThanCashOrSum() {
		String json = """
				{
				  "code": 0,
				  "data": {
				    "available_balance": 8.0,
				    "voucher_balance": 10.0,
				    "cash_balance": -2.0
				  },
				  "status": true
				}
				""";
		server.enqueue(new MockResponse().setBody(json)
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("sk-kimi").fetch();

		assertThat(usage.getRemaining()).isEqualTo(8.0d);
	}

	@Test
	void exhaustedAccountReportsZeroRatherThanFailing() {
		String json = """
				{"code":0,"data":{"available_balance":0,"voucher_balance":0,"cash_balance":0},"status":true}
				""";
		server.enqueue(new MockResponse().setBody(json)
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("sk-kimi").fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getRemaining()).isZero();
	}

	/** A .com key on the .ai host answers 401; the card has to say so rather than show a blank. */
	@Test
	void rejectedKeySurfacesAsError() {
		server.enqueue(new MockResponse().setResponseCode(401)
				.setBody("{\"error\":{\"message\":\"invalid api key\",\"type\":\"authentication_error\"}}")
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("sk-wrong-origin").fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.ERROR);
		assertThat(usage.getMessage()).isNotBlank();
	}

	@Test
	void disabledWithoutKey() {
		ProviderUsage usage = provider("").fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.DISABLED);
		assertThat(server.getRequestCount()).isZero();
	}

	private KimiProvider provider(String apiKey) {
		Config config = new Config();
		config.setEnabled(true);
		config.setBaseUrl(server.url("/").toString());
		config.setApiKey(apiKey);

		ProviderProperties properties = new ProviderProperties();
		properties.setProviders(Map.of(KimiProvider.PROVIDER_ID, config));
		return new KimiProvider(properties, RestClient.builder());
	}
}
