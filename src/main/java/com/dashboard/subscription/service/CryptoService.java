package com.dashboard.subscription.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.dashboard.subscription.config.AlertProperties;

/**
 * AES-256-GCM encryption for subscription payloads at rest. The 32-byte key arrives base64-encoded
 * from configuration (Secret Manager on deployments); without a key the service is inactive and
 * alert subscriptions are refused. Ciphertext format: base64(iv || ciphertext+tag).
 */
@Service
public class CryptoService {

	private static final int IV_LENGTH_BYTES = 12;
	private static final int TAG_LENGTH_BITS = 128;
	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

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
			cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] combined = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, combined, 0, iv.length);
			System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
			return Base64.getEncoder().encodeToString(combined);
		} catch (Exception exception) {
			throw new IllegalStateException("Encryption failed", exception);
		}
	}

	public String decrypt(String ciphertext) {
		try {
			byte[] combined = Base64.getDecoder().decode(ciphertext);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key(),
					new GCMParameterSpec(TAG_LENGTH_BITS, combined, 0, IV_LENGTH_BYTES));
			byte[] decrypted = cipher.doFinal(combined, IV_LENGTH_BYTES,
					combined.length - IV_LENGTH_BYTES);
			return new String(decrypted, StandardCharsets.UTF_8);
		} catch (Exception exception) {
			throw new IllegalStateException("Decryption failed", exception);
		}
	}

	private SecretKey key() {
		byte[] raw = Base64.getDecoder().decode(properties.getEncryptionKey());
		return new SecretKeySpec(raw, "AES");
	}
}
