package com.dashboard.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.dashboard.subscription.config.AuthProperties;
import com.dashboard.subscription.domain.AuthUser;

class JwtServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-17T09:00:00Z");
	private static final AuthUser USER = new AuthUser("google-sub-1", "user@example.com");

	private final AuthProperties properties = new AuthProperties();
	private final MutableClock clock = new MutableClock(NOW);
	private final JwtService service;

	JwtServiceTest() {
		byte[] secret = new byte[32];
		new SecureRandom().nextBytes(secret);
		properties.setJwtSecret(Base64.getEncoder().encodeToString(secret));
		properties.setGoogleClientId("client-id");
		service = new JwtService(properties, clock);
	}

	@Test
	void roundTripsUser() {
		String token = service.issue(USER);

		assertThat(token).isNotBlank();
		assertThat(service.verify(token)).contains(USER);
	}

	@Test
	void rejectsTamperedToken() {
		String token = service.issue(USER);
		String tampered = token.substring(0, token.length() - 4) + "AAAA";

		assertThat(service.verify(tampered)).isEmpty();
	}

	@Test
	void rejectsExpiredToken() {
		String token = service.issue(USER);
		clock.advance(Duration.ofDays(8));

		assertThat(service.verify(token)).isEmpty();
	}

	@Test
	void acceptsTokenWithinTtl() {
		String token = service.issue(USER);
		clock.advance(Duration.ofDays(6));

		assertThat(service.verify(token)).contains(USER);
	}

	@Test
	void rejectsGarbage() {
		assertThat(service.verify("not-a-jwt")).isEmpty();
		assertThat(service.verify("a.b")).isEmpty();
		assertThat(service.verify("")).isEmpty();
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant start) {
			this.now = start;
		}

		private void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
