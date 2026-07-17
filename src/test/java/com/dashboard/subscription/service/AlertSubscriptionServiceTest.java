package com.dashboard.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dashboard.subscription.config.AlertProperties;
import com.dashboard.subscription.domain.AlertSubscription;
import com.dashboard.subscription.domain.AlertThresholds;
import com.dashboard.subscription.domain.ApiCredentials;
import com.dashboard.subscription.domain.MetricType;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;
import com.dashboard.subscription.service.SubscriptionStore.StoredSubscription;
import com.dashboard.subscription.web.DashboardResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

class AlertSubscriptionServiceTest {

	private final AlertProperties properties = new AlertProperties();
	private final SubscriptionStore subscriptionStore = mock(SubscriptionStore.class);
	private final CachedUsageService cachedUsageService = mock(CachedUsageService.class);
	private final SlackNotifier slackNotifier = mock(SlackNotifier.class);
	private CryptoService cryptoService;
	private AlertSubscriptionService service;

	private static final String WEBHOOK = "https://hooks.slack.com/services/T0/B0/XX";
	private static final Map<String, ApiCredentials> KEYS =
			Map.of("xai", new ApiCredentials("byok-key", "team"));

	@BeforeEach
	void setUp() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		properties.setEncryptionKey(Base64.getEncoder().encodeToString(key));
		properties.setProjectId("test-project");
		cryptoService = new CryptoService(properties);
		service = new AlertSubscriptionService(properties, cryptoService, subscriptionStore,
				cachedUsageService, new AlertEvaluator(), slackNotifier);
	}

	@Test
	void subscribeStoresEncryptedPayloadWithDefaultsApplied() throws Exception {
		when(subscriptionStore.create(anyString(), anyString(), anyString())).thenReturn("doc123");

		String id = service.subscribe(new AlertSubscription(WEBHOOK, KEYS, null), "");

		assertThat(id).isEqualTo("doc123");
		ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
		verify(subscriptionStore).create(stored.capture(), anyString(), anyString());
		assertThat(stored.getValue()).doesNotContain("byok-key").doesNotContain("hooks.slack.com");
		AlertSubscription decrypted = new ObjectMapper()
				.readValue(cryptoService.decrypt(stored.getValue()), AlertSubscription.class);
		assertThat(decrypted.webhookUrl()).isEqualTo(WEBHOOK);
		assertThat(decrypted.thresholds()).isEqualTo(new AlertThresholds(80.0d, 5.0d, null));
	}

	@Test
	void subscribeSendsConfirmationToWebhook() {
		when(subscriptionStore.create(anyString(), anyString(), anyString())).thenReturn("doc123");

		service.subscribe(new AlertSubscription(WEBHOOK, KEYS, null), "");

		verify(slackNotifier).send(eq(WEBHOOK), contains("구독"));
	}

	@Test
	void subscribeRejectsWebhookThatFailsDelivery() {
		org.mockito.Mockito.doThrow(new RuntimeException("404"))
				.when(slackNotifier).send(anyString(), anyString());

		assertThatThrownBy(() -> service.subscribe(new AlertSubscription(WEBHOOK, KEYS, null), ""))
				.isInstanceOf(IllegalArgumentException.class);
		verify(subscriptionStore, never()).create(anyString(), anyString(), anyString());
	}

	@Test
	void subscribeReplacesExistingSubscriptionForSameWebhook() throws Exception {
		StoredSubscription existing = storedSubscription("");
		when(subscriptionStore.list()).thenReturn(List.of(existing));
		when(subscriptionStore.create(anyString(), anyString(), anyString())).thenReturn("doc-new");

		String id = service.subscribe(new AlertSubscription(WEBHOOK, KEYS, null), "");

		assertThat(id).isEqualTo("doc-new");
		verify(subscriptionStore).delete("doc123");
	}

	@Test
	void subscribeRejectsWhenSubscriptionLimitReached() throws Exception {
		java.util.List<StoredSubscription> full = new java.util.ArrayList<>();
		for (int index = 0; index < 100; index++) {
			full.add(new StoredSubscription("id-" + index, "enc", "other-hash-" + index, "", ""));
		}
		when(subscriptionStore.list()).thenReturn(full);

		assertThatThrownBy(() -> service.subscribe(new AlertSubscription(WEBHOOK, KEYS, null), ""))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("limit");
		verify(subscriptionStore, never()).create(anyString(), anyString(), anyString());
	}

	@Test
	void subscribeRejectsNonSlackWebhook() {
		assertThatThrownBy(() -> service.subscribe(
				new AlertSubscription("https://evil.example.com/hook", KEYS, null), ""))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void subscribeEnforcesPerUserLimit() {
		java.util.List<StoredSubscription> owned = new java.util.ArrayList<>();
		for (int index = 0; index < 3; index++) {
			owned.add(new StoredSubscription("id-" + index, "enc", "hash-" + index, "user-1", ""));
		}
		when(subscriptionStore.list()).thenReturn(owned);

		assertThatThrownBy(() -> service.subscribe(new AlertSubscription(WEBHOOK, KEYS, null), "user-1"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("per-user");
	}

	@Test
	void unsubscribeByOwnerRemovesOwnSubscription() {
		when(subscriptionStore.list()).thenReturn(List.of(
				new StoredSubscription("doc123", "enc", "h", "user-1", "")));

		service.unsubscribe("doc123", "user-1");

		verify(subscriptionStore).delete("doc123");
	}

	@Test
	void unsubscribeRejectsForeignSubscription() {
		when(subscriptionStore.list()).thenReturn(List.of(
				new StoredSubscription("doc123", "enc", "h", "user-1", "")));

		assertThatThrownBy(() -> service.unsubscribe("doc123", "user-2"))
				.isInstanceOf(IllegalArgumentException.class);
		verify(subscriptionStore, never()).delete(anyString());
	}

	@Test
	void subscribeRejectsMissingKeys() {
		assertThatThrownBy(() -> service.subscribe(new AlertSubscription(WEBHOOK, Map.of(), null), ""))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void subscribeRefusedWhenNotConfigured() {
		properties.setEncryptionKey("");

		assertThatThrownBy(() -> service.subscribe(new AlertSubscription(WEBHOOK, KEYS, null), ""))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void sweepNotifiesSubscriberWebhookOnNewAlerts() throws Exception {
		StoredSubscription stored = storedSubscription("");
		when(subscriptionStore.list()).thenReturn(List.of(stored));
		when(cachedUsageService.collect(KEYS)).thenReturn(response(quotaAt(92.0d)));

		SweepResult result = service.sweep();

		assertThat(result).isEqualTo(new SweepResult(true, 1, 1));
		verify(slackNotifier).send(eq(WEBHOOK), contains("92"));
		verify(subscriptionStore).updateFingerprint(eq("doc123"), startsWith("ElevenLabs"));
	}

	@Test
	void sweepSkipsUnchangedAlertState() throws Exception {
		when(cachedUsageService.collect(KEYS)).thenReturn(response(quotaAt(92.0d)));
		StoredSubscription first = storedSubscription("");
		when(subscriptionStore.list()).thenReturn(List.of(first));
		service.sweep();
		ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
		verify(subscriptionStore).updateFingerprint(eq("doc123"), fingerprint.capture());

		when(subscriptionStore.list())
				.thenReturn(List.of(storedSubscription(fingerprint.getValue())));
		SweepResult second = service.sweep();

		assertThat(second.notified()).isZero();
		verify(slackNotifier).send(anyString(), anyString());
	}

	@Test
	void sweepDisabledWithoutConfiguration() {
		properties.setEncryptionKey("");

		assertThat(service.sweep()).isEqualTo(new SweepResult(false, 0, 0));
		verify(subscriptionStore, never()).list();
	}

	@Test
	void sweepIsolatesBrokenSubscription() throws Exception {
		StoredSubscription broken = new StoredSubscription("bad", "not-decryptable", "h-x", "", "");
		StoredSubscription healthy = storedSubscription("");
		when(subscriptionStore.list()).thenReturn(List.of(broken, healthy));
		when(cachedUsageService.collect(KEYS)).thenReturn(response(quotaAt(92.0d)));

		SweepResult result = service.sweep();

		assertThat(result).isEqualTo(new SweepResult(true, 2, 1));
		verify(slackNotifier).send(eq(WEBHOOK), any());
	}

	private StoredSubscription storedSubscription(String fingerprint) throws Exception {
		AlertSubscription subscription = new AlertSubscription(WEBHOOK, KEYS,
				new AlertThresholds(80.0d, 5.0d, null));
		String encrypted = cryptoService.encrypt(
				new ObjectMapper().writeValueAsString(subscription));
		return new StoredSubscription("doc123", encrypted,
				AlertSubscriptionService.webhookHash(WEBHOOK), "", fingerprint);
	}

	private ProviderUsage quotaAt(double percent) {
		return ProviderUsage.builder()
				.providerId("elevenlabs")
				.displayName("ElevenLabs")
				.metricType(MetricType.QUOTA)
				.status(ProviderStatus.OK)
				.used(percent)
				.limit(100.0d)
				.build();
	}

	private DashboardResponse response(ProviderUsage usage) {
		return DashboardResponse.builder()
				.generatedAt(Instant.parse("2026-07-17T00:00:00Z"))
				.providers(List.of(usage))
				.build();
	}
}

