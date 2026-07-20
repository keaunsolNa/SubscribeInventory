package com.dashboard.subscription.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import com.dashboard.subscription.domain.ApiCredentials;

/**
 * Deterministic SHA-256 digest of a credential set; sorted so map iteration order never matters.
 * Cache entries and usage-history documents are keyed by this digest, never by plaintext keys.
 */
public final class CredentialFingerprint {

	private CredentialFingerprint() {
	}

	public static String of(Map<String, ApiCredentials> credentials) {
		StringBuilder canonical = new StringBuilder();
		new TreeMap<>(credentials).forEach((providerId, entry) -> canonical
				.append(providerId).append(' ')
				.append(entry.apiKey() == null ? "" : entry.apiKey()).append(' ')
				.append(entry.teamId() == null ? "" : entry.teamId()).append(' '));
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(
					digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}
}
