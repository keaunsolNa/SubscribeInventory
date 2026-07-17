package com.dashboard.subscription.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.AuthProperties;
import com.dashboard.subscription.domain.AuthUser;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Validates a Google ID token via Google's tokeninfo endpoint and checks it was issued for our
 * OAuth client. Kept REST-based so no Google SDK dependency is needed; login happens once per
 * session so the extra round-trip is negligible.
 */
@Service
public class GoogleTokenVerifier {

	private final AuthProperties properties;
	private final RestClient restClient;

	public GoogleTokenVerifier(AuthProperties properties, RestClient.Builder restClientBuilder) {
		this.properties = properties;
		this.restClient = restClientBuilder.build();
	}

	public AuthUser verify(String idToken) {
		JsonNode body;
		try {
			body = restClient.get()
					.uri(properties.getTokeninfoBaseUrl() + "/tokeninfo?id_token={token}", idToken)
					.retrieve()
					.body(JsonNode.class);
		} catch (Exception exception) {
			throw new IllegalArgumentException("Google token verification failed");
		}
		if (!properties.getGoogleClientId().equals(body.path("aud").asText())) {
			throw new IllegalArgumentException("Google token audience mismatch");
		}
		return new AuthUser(body.path("sub").asText(), body.path("email").asText());
	}
}
