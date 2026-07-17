package com.dashboard.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.AlertProperties;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class SlackNotifierTest {

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
	void sendPostsTextAsSlackJson() throws InterruptedException {
		server.enqueue(new MockResponse().setBody("ok"));
		AlertProperties properties = new AlertProperties();
		properties.setWebhookUrl(server.url("/services/T000/B000/XXX").toString());
		SlackNotifier notifier = new SlackNotifier(properties, RestClient.builder());

		notifier.send("잔여량 경고");

		RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
		assertThat(recorded).as("no request reached the webhook").isNotNull();
		assertThat(recorded.getMethod()).isEqualTo("POST");
		assertThat(recorded.getPath()).isEqualTo("/services/T000/B000/XXX");
		assertThat(recorded.getHeader("Content-Type")).contains("application/json");
		assertThat(recorded.getBody().readUtf8()).contains("\"text\"").contains("잔여량 경고");
	}
}
