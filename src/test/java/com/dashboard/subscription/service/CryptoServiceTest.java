package com.dashboard.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.dashboard.subscription.config.AlertProperties;

class CryptoServiceTest {

	private static String randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

	private CryptoService serviceWithKey() {
		AlertProperties properties = new AlertProperties();
		properties.setEncryptionKey(randomKey());
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
	void tagsCiphertextWithKeyFingerprint() {
		CryptoService service = serviceWithKey();

		assertThat(service.encrypt("payload")).matches("^k[0-9a-f]{8}:.+");
	}

	@Test
	void decryptsLegacyUntaggedCiphertextWithCurrentKey() {
		AlertProperties properties = new AlertProperties();
		properties.setEncryptionKey(randomKey());
		CryptoService service = new CryptoService(properties);

		String tagged = service.encrypt("legacy payload");
		String untagged = tagged.substring(tagged.indexOf(':') + 1);

		assertThat(service.decrypt(untagged)).isEqualTo("legacy payload");
	}

	@Test
	void decryptsOldKeyCiphertextAfterRotation() {
		String oldKey = randomKey();
		AlertProperties before = new AlertProperties();
		before.setEncryptionKey(oldKey);
		String sealedWithOldKey = new CryptoService(before).encrypt("survives rotation");

		AlertProperties after = new AlertProperties();
		after.setEncryptionKey(randomKey());
		after.setOldEncryptionKeys(oldKey);
		CryptoService rotated = new CryptoService(after);

		assertThat(rotated.decrypt(sealedWithOldKey)).isEqualTo("survives rotation");
		assertThat(rotated.decrypt(rotated.encrypt("new data"))).isEqualTo("new data");
	}

	@Test
	void rejectsCiphertextTaggedWithUnknownKey() {
		String sealed = serviceWithKey().encrypt("orphaned");
		CryptoService other = serviceWithKey();

		assertThatThrownBy(() -> other.decrypt(sealed))
				.isInstanceOf(IllegalStateException.class);
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
