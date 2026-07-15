package com.dashboard.subscription.provider;

import com.dashboard.subscription.domain.ApiCredentials;
import com.dashboard.subscription.domain.ProviderUsage;

/**
 * One subscription-based provider the dashboard can query. Implementations never throw from
 * {@link #fetch()}; failures are reported as an ERROR snapshot instead.
 */
public interface UsageProvider {

	String providerId();

	ProviderUsage fetch();

	/**
	 * Same as {@link #fetch()} but with a BYOK per-request credential override; blank fields
	 * fall back to the server-side configuration.
	 */
	ProviderUsage fetch(ApiCredentials credentials);
}
