package com.dashboard.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.dashboard.subscription.config.AlertProperties;

class CryptoServiceTest {

	private CryptoService serviceWithKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		AlertProperties properties = new AlertProperties();
		properties.setEncryptionKey(Base64.getEncoder().encodeToString(key));
		return new CryptoService(properties);
	}

	@Test
	void roundTripsPlaintext() {
		CryptoService service = serviceWithKey();

		String ciphertext = service.encrypt("{\"webhookUrl\":\"https://hooks.slack.com/x\"}");

		assertThat(ciphertext).isNotEqualTo("{\"webhookUrl\":\"https://hooks.slack.com/x\"}");
		assertThat(service.decrypt(ciphertext))
				.isEqualTo("{\"webhookUrl\":\"https://hooks.slack.com/x\"}");
	}

	@Test
	void usesFreshIvPerEncryption() {
		CryptoService service = serviceWithKey();

		assertThat(service.encrypt("same")).isNotEqualTo(service.encrypt("same"));
	}

	@Test
	void inactiveWithoutKey() {
		CryptoService service = new CryptoService(new AlertProperties());

		assertThat(service.isActive()).isFalse();
	}

	@Test
	void activeWithKey() {
		assertThat(serviceWithKey().isActive()).isTrue();
	}
}
