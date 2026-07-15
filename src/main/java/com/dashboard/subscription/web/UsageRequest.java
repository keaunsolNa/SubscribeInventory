package com.dashboard.subscription.web;

import java.util.Map;

import com.dashboard.subscription.domain.ApiCredentials;

/**
 * BYOK request body: per-provider credentials keyed by provider id (elevenlabs, xai, openai, anthropic).
 * The server relays them upstream for this request only and never stores them.
 */
public record UsageRequest(Map<String, ApiCredentials> keys) {
}
