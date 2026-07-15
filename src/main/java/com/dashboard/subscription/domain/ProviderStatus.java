package com.dashboard.subscription.domain;

/**
 * Result state of a single provider lookup.
 *
 * <p>OK: the provider answered and the figures are populated.
 * DISABLED: the provider is turned off or has no credential, so no call was made.
 * ERROR: the call was attempted but failed (network, auth, or parsing).
 */
public enum ProviderStatus {

	OK,
	DISABLED,
	ERROR
}
