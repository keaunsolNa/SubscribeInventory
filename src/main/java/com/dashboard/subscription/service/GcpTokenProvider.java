package com.dashboard.subscription.service;

/**
 * Supplies an OAuth access token for Google APIs. The runtime implementation reads the Cloud Run
 * metadata server; tests substitute a stub.
 */
public interface GcpTokenProvider {

	String accessToken();
}
