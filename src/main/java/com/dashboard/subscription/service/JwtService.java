package com.dashboard.subscription.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.dashboard.subscription.config.AuthProperties;
import com.dashboard.subscription.domain.AuthUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Issues and verifies the dashboard's own session tokens (JWT, HS256) after Google login.
 * Hand-rolled compact JWT to avoid an extra dependency; the secret arrives base64-encoded from
 * configuration (Secret Manager on deployments).
 */
@Service
public class JwtService {

	private static final Duration TOKEN_TTL = Duration.ofDays(7);
	private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private final AuthProperties properties;
	private final Clock clock;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public JwtService(AuthProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	public String issue(AuthUser user) {
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("sub", user.id());
		payload.put("email", user.email());
		payload.put("exp", clock.instant().plus(TOKEN_TTL).getEpochSecond());

		String content = encode(HEADER_JSON.getBytes(StandardCharsets.UTF_8))
				+ "." + encode(payload.toString().getBytes(StandardCharsets.UTF_8));
		return content + "." + encode(sign(content));
	}

	public Optional<AuthUser> verify(String token) {
		try {
			String[] parts = token.split("\\.");
			if (parts.length != 3) {
				return Optional.empty();
			}
			String content = parts[0] + "." + parts[1];
			byte[] expected = sign(content);
			byte[] provided = Base64.getUrlDecoder().decode(parts[2]);
			if (!MessageDigest.isEqual(expected, provided)) {
				return Optional.empty();
			}
			JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
			if (payload.path("exp").asLong() < clock.instant().getEpochSecond()) {
				return Optional.empty();
			}
			return Optional.of(new AuthUser(
					payload.path("sub").asText(), payload.path("email").asText()));
		} catch (Exception exception) {
			return Optional.empty();
		}
	}

	private byte[] sign(String content) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(
					Base64.getDecoder().decode(properties.getJwtSecret()), HMAC_ALGORITHM));
			return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
		} catch (Exception exception) {
			throw new IllegalStateException("JWT signing failed", exception);
		}
	}

	private String encode(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
