package com.dashboard.subscription.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

	/** Why a session token was refused. Reported so a dead session is diagnosable from the log. */
	public enum Rejection {
		/** The token verified. */
		NONE,
		/** The request carried no {@code Authorization: Bearer} header. */
		MISSING,
		/** Not three base64url segments, or a segment would not decode. */
		MALFORMED,
		/** Signature mismatch — a rotated secret, a token from another deployment, or tampering. */
		BAD_SIGNATURE,
		/** Well-formed and correctly signed, but past its {@code exp}. */
		EXPIRED
	}

	/**
	 * Outcome of {@link #inspect(String)}. {@code expiresAt} is present whenever the payload could
	 * be read, so an expired session can be reported with the moment it actually lapsed.
	 */
	public record Verification(AuthUser user, Rejection rejection, Instant expiresAt) {

		public static Verification accepted(AuthUser user, Instant expiresAt) {
			return new Verification(user, Rejection.NONE, expiresAt);
		}

		public static Verification refused(Rejection rejection) {
			return new Verification(null, rejection, null);
		}

		public static Verification expired(Instant expiresAt) {
			return new Verification(null, Rejection.EXPIRED, expiresAt);
		}

		public boolean valid() {
			return user != null;
		}
	}

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
		return Optional.ofNullable(inspect(token).user());
	}

	/**
	 * The same check as {@link #verify(String)}, but naming the reason a token was refused instead
	 * of collapsing every failure into an empty result — an expired session and a bad signature
	 * need very different responses from the operator.
	 */
	public Verification inspect(String token) {
		String[] parts = token.split("\\.");
		if (parts.length != 3) {
			return Verification.refused(Rejection.MALFORMED);
		}
		byte[] provided;
		JsonNode payload;
		try {
			provided = Base64.getUrlDecoder().decode(parts[2]);
			payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
		} catch (Exception exception) {
			return Verification.refused(Rejection.MALFORMED);
		}
		try {
			if (!MessageDigest.isEqual(sign(parts[0] + "." + parts[1]), provided)) {
				return Verification.refused(Rejection.BAD_SIGNATURE);
			}
		} catch (RuntimeException exception) {
			// A secret this service cannot sign with is a server fault, but callers have always
			// seen a 401 here rather than a 500 — keep that contract.
			return Verification.refused(Rejection.BAD_SIGNATURE);
		}
		long exp = payload.path("exp").asLong();
		if (exp < clock.instant().getEpochSecond()) {
			return Verification.expired(Instant.ofEpochSecond(exp));
		}
		return Verification.accepted(
				new AuthUser(payload.path("sub").asText(), payload.path("email").asText()),
				Instant.ofEpochSecond(exp));
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
