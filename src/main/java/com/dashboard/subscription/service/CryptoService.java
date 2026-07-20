package com.dashboard.subscription.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.dashboard.subscription.config.AlertProperties;

/**
 * AES-256-GCM encryption for subscription payloads at rest. The 32-byte keys arrive base64-encoded
 * from configuration (Secret Manager on deployments); without a current key the service is
 * inactive and alert subscriptions are refused.
 *
 * <p>Ciphertext format: {@code k<fp>:base64(iv || ciphertext+tag)} where {@code fp} is the first
 * eight hex chars of the key's SHA-256, so after a key rotation each document still names the key
 * that sealed it. Untagged ciphertext predates tagging and decrypts with the current key. To
 * rotate: point {@code encryption-key} at the new key, append the old one to
 * {@code old-encryption-keys}, and keep it there until no stored payload carries its tag.
 */
@Service
public class CryptoService {

	private static final int IV_LENGTH_BYTES = 12;
	private static final int TAG_LENGTH_BITS = 128;
	private static final int FINGERPRINT_BYTES = 4;
	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final Pattern TAGGED_FORMAT = Pattern.compile("^k([0-9a-f]{8}):(.+)$");

	private final AlertProperties properties;
	private final SecureRandom secureRandom = new SecureRandom();

	public CryptoService(AlertProperties properties) {
		this.properties = properties;
	}

	public boolean isActive() {
		return StringUtils.hasText(properties.getEncryptionKey());
	}

	public String encrypt(String plaintext) {
		try {
			byte[] iv = new byte[IV_LENGTH_BYTES];
			secureRandom.nextBytes(iv);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, toKey(properties.getEncryptionKey()),
					new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] combined = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, combined, 0, iv.length);
			System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
			return "k" + fingerprint(properties.getEncryptionKey()) + ":"
					+ Base64.getEncoder().encodeToString(combined);
		} catch (Exception exception) {
			throw new IllegalStateException("Encryption failed", exception);
		}
	}

	public String decrypt(String ciphertext) {
		try {
			Matcher tagged = TAGGED_FORMAT.matcher(ciphertext);
			SecretKey key;
			String encoded;
			if (tagged.matches()) {
				key = keyByFingerprint(tagged.group(1));
				encoded = tagged.group(2);
			} else {
				key = toKey(properties.getEncryptionKey());
				encoded = ciphertext;
			}
			byte[] combined = Base64.getDecoder().decode(encoded);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key,
					new GCMParameterSpec(TAG_LENGTH_BITS, combined, 0, IV_LENGTH_BYTES));
			byte[] decrypted = cipher.doFinal(combined, IV_LENGTH_BYTES,
					combined.length - IV_LENGTH_BYTES);
			return new String(decrypted, StandardCharsets.UTF_8);
		} catch (Exception exception) {
			throw new IllegalStateException("Decryption failed", exception);
		}
	}

	private SecretKey keyByFingerprint(String requested) {
		for (String encodedKey : configuredKeys()) {
			if (fingerprint(encodedKey).equals(requested)) {
				return toKey(encodedKey);
			}
		}
		throw new IllegalStateException("No configured encryption key matches tag k" + requested);
	}

	/** Current key first, then any retired keys kept around for decryption after rotation. */
	private List<String> configuredKeys() {
		List<String> keys = new ArrayList<>();
		keys.add(properties.getEncryptionKey());
		String oldKeys = properties.getOldEncryptionKeys();
		if (StringUtils.hasText(oldKeys)) {
			for (String encodedKey : oldKeys.split(",")) {
				if (StringUtils.hasText(encodedKey)) {
					keys.add(encodedKey.trim());
				}
			}
		}
		return keys;
	}

	private static String fingerprint(String encodedKey) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(Base64.getDecoder().decode(encodedKey));
			return HexFormat.of().formatHex(digest, 0, FINGERPRINT_BYTES);
		} catch (Exception exception) {
			throw new IllegalStateException("Key fingerprint failed", exception);
		}
	}

	private static SecretKey toKey(String encodedKey) {
		return new SecretKeySpec(Base64.getDecoder().decode(encodedKey), "AES");
	}
}
