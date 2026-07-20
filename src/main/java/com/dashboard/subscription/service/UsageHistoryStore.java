package com.dashboard.subscription.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.AlertProperties;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;
import com.dashboard.subscription.web.DashboardResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * Firestore-backed hourly usage history, via the same REST approach as {@link SubscriptionStore}.
 * One document per credential fingerprint per UTC day ({@code <fp16>_<yyyyMMdd>}) with hour slots
 * {@code h00..h23}, each a compact JSON snapshot of provider balances/costs. Only numbers and
 * provider ids are stored — never keys — and the fingerprint is irreversible, so history leaks
 * nothing usable on its own. Writes are best-effort: a history failure must never break a usage
 * request, so callers get a log line instead of an exception.
 */
@Slf4j
@Service
public class UsageHistoryStore {

	private static final String COLLECTION = "usageHistory";
	private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
	private static final int FINGERPRINT_PREFIX_CHARS = 16;

	private final AlertProperties properties;
	private final GcpTokenProvider tokenProvider;
	private final RestClient restClient;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final Clock clock;

	public UsageHistoryStore(AlertProperties properties, GcpTokenProvider tokenProvider,
			RestClient.Builder restClientBuilder, Clock clock) {
		this.properties = properties;
		this.tokenProvider = tokenProvider;
		this.restClient = restClientBuilder.build();
		this.clock = clock;
	}

	public record HistoryPoint(Instant timestamp, List<ProviderPoint> entries) {
	}

	public record ProviderPoint(String providerId, Double remaining, Double cost) {
	}

	public boolean isActive() {
		return StringUtils.hasText(properties.getProjectId());
	}

	/** Upserts the current UTC hour slot for this fingerprint; no-op when nothing is measurable. */
	public void record(String fingerprint, DashboardResponse response) {
		if (!isActive()) {
			return;
		}
		String snapshot = snapshotJson(response);
		if (snapshot == null) {
			return;
		}
		Instant now = clock.instant();
		String day = DAY_FORMAT.format(LocalDate.ofInstant(now, ZoneOffset.UTC));
		String hourField = "h%02d".formatted(now.atZone(ZoneOffset.UTC).getHour());
		try {
			ObjectNode document = objectMapper.createObjectNode();
			ObjectNode fields = document.putObject("fields");
			fields.putObject("fingerprint").put("stringValue", fingerprint);
			fields.putObject("day").put("stringValue", day);
			fields.putObject(hourField).put("stringValue", snapshot);

			restClient.patch()
					.uri(documentUrl(fingerprint, day) + "?updateMask.fieldPaths=fingerprint"
							+ "&updateMask.fieldPaths=day&updateMask.fieldPaths=" + hourField)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.accessToken())
					.contentType(MediaType.APPLICATION_JSON)
					.body(document.toString())
					.retrieve()
					.toBodilessEntity();
		} catch (Exception exception) {
			log.warn("Usage history write failed: {}", exception.toString());
		}
	}

	/** Hour-resolution points for the last {@code days} UTC days, oldest first. */
	public List<HistoryPoint> recent(String fingerprint, int days) {
		List<HistoryPoint> points = new ArrayList<>();
		if (!isActive()) {
			return points;
		}
		LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
		for (int offset = days - 1; offset >= 0; offset--) {
			LocalDate date = today.minusDays(offset);
			JsonNode document = fetchDocument(fingerprint, DAY_FORMAT.format(date));
			if (document == null) {
				continue;
			}
			JsonNode fields = document.path("fields");
			for (int hour = 0; hour < 24; hour++) {
				String snapshot = fields.path("h%02d".formatted(hour)).path("stringValue").asText("");
				if (snapshot.isEmpty()) {
					continue;
				}
				points.add(new HistoryPoint(
						date.atStartOfDay(ZoneOffset.UTC).plusHours(hour).toInstant(),
						parseSnapshot(snapshot)));
			}
		}
		return points;
	}

	/** Compact JSON of measurable OK providers, or null when there is nothing worth storing. */
	String snapshotJson(DashboardResponse response) {
		ArrayNode entries = objectMapper.createArrayNode();
		for (ProviderUsage usage : response.getProviders()) {
			if (usage.getStatus() != ProviderStatus.OK
					|| (usage.getRemaining() == null && usage.getCost() == null)) {
				continue;
			}
			ObjectNode entry = entries.addObject();
			entry.put("p", usage.getProviderId());
			if (usage.getRemaining() != null) {
				entry.put("r", usage.getRemaining());
			}
			if (usage.getCost() != null) {
				entry.put("c", usage.getCost());
			}
		}
		return entries.isEmpty() ? null : entries.toString();
	}

	List<ProviderPoint> parseSnapshot(String snapshot) {
		List<ProviderPoint> entries = new ArrayList<>();
		try {
			for (JsonNode entry : objectMapper.readTree(snapshot)) {
				entries.add(new ProviderPoint(
						entry.path("p").asText(),
						entry.hasNonNull("r") ? entry.path("r").asDouble() : null,
						entry.hasNonNull("c") ? entry.path("c").asDouble() : null));
			}
		} catch (Exception exception) {
			log.warn("Usage history snapshot unreadable: {}", exception.toString());
		}
		return entries;
	}

	private JsonNode fetchDocument(String fingerprint, String day) {
		try {
			return restClient.get()
					.uri(documentUrl(fingerprint, day))
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.accessToken())
					.retrieve()
					.body(JsonNode.class);
		} catch (Exception exception) {
			// Missing days are normal (404); anything else is logged and skipped.
			if (!exception.getMessage().contains("404")) {
				log.warn("Usage history read failed for {}: {}", day, exception.toString());
			}
			return null;
		}
	}

	private String documentUrl(String fingerprint, String day) {
		String docId = fingerprint.substring(0, Math.min(FINGERPRINT_PREFIX_CHARS,
				fingerprint.length())) + "_" + day;
		return properties.getFirestoreBaseUrl() + "/v1/projects/" + properties.getProjectId()
				+ "/databases/(default)/documents/" + COLLECTION + "/" + docId;
	}
}
