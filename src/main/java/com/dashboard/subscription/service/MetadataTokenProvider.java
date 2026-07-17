package com.dashboard.subscription.service;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Fetches the runtime service account's access token from the GCE/Cloud Run metadata server.
 * Only reachable when running on GCP; local runs never get here because subscriptions are
 * inactive without the deployment-only configuration.
 */
@Component
public class MetadataTokenProvider implements GcpTokenProvider {

	private static final String TOKEN_URL =
			"http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token";

	private final RestClient restClient;

	public MetadataTokenProvider(RestClient.Builder restClientBuilder) {
		this.restClient = restClientBuilder.build();
	}

	@Override
	public String accessToken() {
		JsonNode body = restClient.get()
				.uri(TOKEN_URL)
				.header("Metadata-Flavor", "Google")
				.retrieve()
				.body(JsonNode.class);
		return body.path("access_token").asText();
	}
}
