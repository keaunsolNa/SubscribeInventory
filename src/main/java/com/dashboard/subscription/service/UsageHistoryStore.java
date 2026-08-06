package com.dashboard.subscription.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

	/**
	 * Balance actually consumed over an observed window. {@code samples} and the window bounds are
	 * part of the result on purpose: history is only written while someone is looking at the
	 * dashboard, so a month with few samples is an under-count, and the caller has to be able to
	 * say so rather than present a gap as a fact.
	 */
	public record ProviderSpend(String providerId, double consumed, Instant from, Instant to,
			int samples) {
	}

	/**
	 * Sums balance decreases per provider, oldest to newest. Increases are top-ups, not negative
	 * spend, so they are skipped rather than subtracted — otherwise a mid-month refill would erase
	 * the consumption that came before it. Cost series are ignored here: providers that report a
	 * cumulative month-to-date figure already answer this question exactly, and the live value beats
	 * anything derived from samples.
	 */
	static List<ProviderSpend> consumption(List<HistoryPoint> points) {
		Map<String, Double> consumed = new LinkedHashMap<>();
		Map<String, Double> previous = new LinkedHashMap<>();
		Map<String, Instant> from = new LinkedHashMap<>();
		Map<String, Instant> to = new LinkedHashMap<>();
		Map<String, Integer> samples = new LinkedHashMap<>();
		for (HistoryPoint point : points) {
			for (ProviderPoint entry : point.entries()) {
				if (entry.remaining() == null) {
					continue;
				}
				String id = entry.providerId();
				Double last = previous.get(id);
				if (last != null && last > entry.remaining()) {
					consumed.merge(id, last - entry.remaining(), Double::sum);
				}
				previous.put(id, entry.remaining());
				from.putIfAbsent(id, point.timestamp());
				to.put(id, point.timestamp());
				samples.merge(id, 1, Integer::sum);
			}
		}
		List<ProviderSpend> result = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : samples.entrySet()) {
			String id = entry.getKey();
			result.add(new ProviderSpend(id, consumed.getOrDefault(id, 0d), from.get(id), to.get(id),
					entry.getValue()));
		}
		return result;
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
			appendDay(points, document, date);
		}
		return points;
	}

	/**
	 * Balance consumed per provider over one UTC month. A month is up to 31 day-documents, so this
	 * reads them with a single {@code documents:batchGet} rather than the day-at-a-time loop
	 * {@link #recent} uses — 31 sequential round trips would dominate the request.
	 */
	public List<ProviderSpend> monthlyConsumption(String fingerprint, YearMonth month) {
		return consumption(monthPoints(fingerprint, month));
	}

	List<HistoryPoint> monthPoints(String fingerprint, YearMonth month) {
		List<HistoryPoint> points = new ArrayList<>();
		if (!isActive()) {
			return points;
		}
		LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
		LocalDate last = month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth();
		if (last.isBefore(month.atDay(1))) {
			return points;
		}
		List<LocalDate> days = new ArrayList<>();
		for (LocalDate date = month.atDay(1); !date.isAfter(last); date = date.plusDays(1)) {
			days.add(date);
		}
		ObjectNode request = objectMapper.createObjectNode();
		ArrayNode names = request.putArray("documents");
		for (LocalDate date : days) {
			names.add(documentName(fingerprint, DAY_FORMAT.format(date)));
		}
		JsonNode response;
		try {
			response = restClient.post()
					.uri(properties.getFirestoreBaseUrl() + "/v1/projects/" + properties.getProjectId()
							+ "/databases/(default)/documents:batchGet")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.accessToken())
					.contentType(MediaType.APPLICATION_JSON)
					.body(request.toString())
					.retrieve()
					.body(JsonNode.class);
		} catch (Exception exception) {
			log.warn("Usage history month read failed for {}: {}", month, exception.toString());
			return points;
		}
		if (response == null || !response.isArray()) {
			return points;
		}
		// batchGet does not promise input order, and misses come back as "missing" entries, so each
		// document is placed by the day it carries rather than by its position in the response.
		Map<LocalDate, JsonNode> byDay = new LinkedHashMap<>();
		for (JsonNode element : response) {
			JsonNode found = element.path("found");
			if (found.isMissingNode()) {
				continue;
			}
			String day = found.path("fields").path("day").path("stringValue").asText("");
			if (day.isEmpty()) {
				continue;
			}
			try {
				byDay.put(LocalDate.parse(day, DAY_FORMAT), found);
			} catch (Exception exception) {
				log.warn("Usage history document has an unreadable day field: {}", day);
			}
		}
		for (LocalDate date : days) {
			JsonNode document = byDay.get(date);
			if (document != null) {
				appendDay(points, document, date);
			}
		}
		return points;
	}

	private void appendDay(List<HistoryPoint> points, JsonNode document, LocalDate date) {
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

	private String documentName(String fingerprint, String day) {
		String docId = fingerprint.substring(0, Math.min(FINGERPRINT_PREFIX_CHARS,
				fingerprint.length())) + "_" + day;
		return "projects/" + properties.getProjectId() + "/databases/(default)/documents/"
				+ COLLECTION + "/" + docId;
	}

	private String documentUrl(String fingerprint, String day) {
		return properties.getFirestoreBaseUrl() + "/v1/" + documentName(fingerprint, day);
	}
}
