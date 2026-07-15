package com.dashboard.subscription.domain;

/**
 * Kind of figure a provider exposes.
 *
 * <p>QUOTA: consumable allowance that resets each period (used / limit / remaining), e.g. ElevenLabs characters.
 * BALANCE: remaining prepaid credit with no period reset, e.g. xAI prepaid balance.
 * COST: money spent so far in the current period, e.g. OpenAI or Anthropic month-to-date cost.
 */
public enum MetricType {

	QUOTA,
	BALANCE,
	COST
}
