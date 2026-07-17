package com.dashboard.subscription.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.AlertProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Firestore-backed store for alert subscriptions, via the Firestore REST API so no heavy SDK is
 * needed. Documents hold only the encrypted payload plus the last alert fingerprint; plaintext
 * keys or webhooks never reach storage.
 */
@Service
public class SubscriptionStore {

	private static final String COLLECTION = "alertSubscriptions";

	private final AlertProperties properties;
	private final GcpTokenProvider tokenProvider;
	private final RestClient restClient;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public SubscriptionStore(AlertProperties properties, GcpTokenProvider tokenProvider,
			RestClient.Builder restClientBuilder) {
		this.properties = properties;
		this.tokenProvider = tokenProvider;
		this.restClient = restClientBuilder.build();
	}

	public record StoredSubscription(String id, String encryptedPayload, String webhookHash,
			String ownerId, String lastFingerprint) {
	}

	/**
	 * @param webhookHash SHA-256 of the webhook URL, stored in plaintext (irreversible) so
	 *        duplicate subscriptions can be detected without decrypting every payload.
	 * @param ownerId authenticated user id owning this subscription; empty in legacy mode.
	 */
	public String create(String encryptedPayload, String webhookHash, String ownerId) {
		ObjectNode document = objectMapper.createObjectNode();
		ObjectNode fields = document.putObject("fields");
		fields.putObject("payload").put("stringValue", encryptedPayload);
		fields.putObject("webhookHash").put("stringValue", webhookHash);
		fields.putObject("ownerId").put("stringValue", ownerId == null ? "" : ownerId);
		fields.putObject("lastFingerprint").put("stringValue", "");

		JsonNode response = restClient.post()
				.uri(collectionUrl())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.body(document.toString())
				.retrieve()
				.body(JsonNode.class);
		String name = response.path("name").asText();
		return name.substring(name.lastIndexOf('/') + 1);
	}

	public List<StoredSubscription> list() {
		List<StoredSubscription> subscriptions = new ArrayList<>();
		String pageToken = null;
		do {
			String url = collectionUrl() + "?pageSize=300"
					+ (pageToken == null ? "" : "&pageToken=" + pageToken);
			JsonNode response = restClient.get()
					.uri(url)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.accessToken())
					.retrieve()
					.body(JsonNode.class);

			for (JsonNode document : response.path("documents")) {
				String name = document.path("name").asText();
				JsonNode fields = document.path("fields");
				subscriptions.add(new StoredSubscription(
						name.substring(name.lastIndexOf('/') + 1),
						fields.path("payload").path("stringValue").asText(),
						fields.path("webhookHash").path("stringValue").asText(),
						fields.path("ownerId").path("stringValue").asText(),
						fields.path("lastFingerprint").path("stringValue").asText()));
			}
			pageToken = response.hasNonNull("nextPageToken")
					? response.path("nextPageToken").asText() : null;
		} while (pageToken != null && !pageToken.isEmpty());
		return subscriptions;
	}

	public void updateFingerprint(String id, String fingerprint) {
		ObjectNode document = objectMapper.createObjectNode();
		document.putObject("fields")
				.putObject("lastFingerprint").put("stringValue", fingerprint);

		restClient.patch()
				.uri(collectionUrl() + "/" + id + "?updateMask.fieldPaths=lastFingerprint")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.body(document.toString())
				.retrieve()
				.toBodilessEntity();
	}

	public void delete(String id) {
		restClient.delete()
				.uri(collectionUrl() + "/" + id)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.accessToken())
				.retrieve()
				.toBodilessEntity();
	}

	private String collectionUrl() {
		return properties.getFirestoreBaseUrl() + "/v1/projects/" + properties.getProjectId()
				+ "/databases/(default)/documents/" + COLLECTION;
	}
}
