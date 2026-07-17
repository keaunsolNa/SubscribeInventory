package com.dashboard.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.AlertProperties;
import com.dashboard.subscription.service.SubscriptionStore.StoredSubscription;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class SubscriptionStoreTest {

	private MockWebServer server;
	private SubscriptionStore store;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		AlertProperties properties = new AlertProperties();
		properties.setProjectId("test-project");
		properties.setFirestoreBaseUrl(server.url("/").toString().replaceAll("/$", ""));
		store = new SubscriptionStore(properties, () -> "test-oauth-token", RestClient.builder());
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	void createPostsEncryptedPayloadAndReturnsDocumentId() throws InterruptedException {
		server.enqueue(new MockResponse()
				.setBody("{\"name\":\"projects/test-project/databases/(default)/documents/alertSubscriptions/doc123\"}")
				.addHeader("Content-Type", "application/json"));

		String id = store.create("ENCRYPTED");

		assertThat(id).isEqualTo("doc123");
		RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
		assertThat(recorded.getMethod()).isEqualTo("POST");
		assertThat(recorded.getPath())
				.isEqualTo("/v1/projects/test-project/databases/(default)/documents/alertSubscriptions");
		assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-oauth-token");
		assertThat(recorded.getBody().readUtf8()).contains("ENCRYPTED");
	}

	@Test
	void listParsesDocumentsWithFingerprints() {
		server.enqueue(new MockResponse()
				.setBody("""
						{"documents":[
						  {"name":"projects/p/databases/(default)/documents/alertSubscriptions/a1",
						   "fields":{"payload":{"stringValue":"ENC-A"},"lastFingerprint":{"stringValue":"fp-a"}}},
						  {"name":"projects/p/databases/(default)/documents/alertSubscriptions/b2",
						   "fields":{"payload":{"stringValue":"ENC-B"},"lastFingerprint":{"stringValue":""}}}
						]}
						""")
				.addHeader("Content-Type", "application/json"));

		List<StoredSubscription> subscriptions = store.list();

		assertThat(subscriptions).containsExactly(
				new StoredSubscription("a1", "ENC-A", "fp-a"),
				new StoredSubscription("b2", "ENC-B", ""));
	}

	@Test
	void listReturnsEmptyWhenCollectionMissing() {
		server.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

		assertThat(store.list()).isEmpty();
	}

	@Test
	void updateFingerprintPatchesSingleField() throws InterruptedException {
		server.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

		store.updateFingerprint("doc123", "new-fp");

		RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
		assertThat(recorded.getMethod()).isEqualTo("PATCH");
		assertThat(recorded.getPath()).contains("/alertSubscriptions/doc123")
				.contains("updateMask.fieldPaths=lastFingerprint");
		assertThat(recorded.getBody().readUtf8()).contains("new-fp");
	}

	@Test
	void deleteRemovesDocument() throws InterruptedException {
		server.enqueue(new MockResponse().setBody("{}").addHeader("Content-Type", "application/json"));

		store.delete("doc123");

		RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
		assertThat(recorded.getMethod()).isEqualTo("DELETE");
		assertThat(recorded.getPath()).endsWith("/alertSubscriptions/doc123");
	}
}
