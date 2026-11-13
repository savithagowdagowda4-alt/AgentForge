package com.agentforge.geo;

import com.agentforge.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Context Intelligence: geocoding and nearby-place search through OpenStreetMap Nominatim.
 * Only called for tasks where the user supplied a location; nothing is tracked or stored beyond the task.
 */
@Service
public class GeoService {
    private final boolean enabled;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private long lastCall = 0;

    public record Place(String name, double lat, double lon, String category) {
    }

    public GeoService(@Value("${agentforge.geo.enabled}") boolean enabled, @Value("${agentforge.geo.base-url}") String baseUrl) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
    }

    public boolean enabled() {
        return enabled;
    }

    public Optional<Place> geocode(String query) {
        if (!enabled || query == null || query.isBlank()) return Optional.empty();
        JsonNode arr = call("/search?format=jsonv2&limit=1&q=" + enc(query));
        if (arr == null || !arr.isArray() || arr.isEmpty()) return Optional.empty();
        return Optional.of(place(arr.get(0)));
    }

    public Optional<String> reverse(double lat, double lon) {
        if (!enabled) return Optional.empty();
        JsonNode n = call("/reverse?format=jsonv2&zoom=14&lat=" + lat + "&lon=" + lon);
        return n == null || !n.has("display_name") ? Optional.empty() : Optional.of(n.get("display_name").asText());
    }

    /** Places matching the query inside a box of roughly ±8 km around the point. */
    public List<Place> nearby(String query, double lat, double lon) {
        if (!enabled) return List.of();
        double d = 0.08;
        String box = (lon - d) + "," + (lat + d) + "," + (lon + d) + "," + (lat - d);
        JsonNode arr = call("/search?format=jsonv2&limit=8&bounded=1&viewbox=" + box + "&q=" + enc(query));
        List<Place> out = new ArrayList<>();
        if (arr != null && arr.isArray()) arr.forEach(n -> out.add(place(n)));
        return out;
    }

    private static Place place(JsonNode n) {
        String name = n.path("name").asText("");
        if (name.isBlank()) name = n.path("display_name").asText("");
        return new Place(name, n.path("lat").asDouble(), n.path("lon").asDouble(), n.path("type").asText(n.path("category").asText("")));
    }

    private synchronized JsonNode call(String path) {
        try {
            long wait = 1100 - (System.currentTimeMillis() - lastCall); // Nominatim usage policy: max 1 request/second
            if (wait > 0) Thread.sleep(wait);
            lastCall = System.currentTimeMillis();
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("User-Agent", "AgentForge/2.0 (self-hosted decision-analysis demo)")
                    .header("Accept-Language", "en")
                    .timeout(Duration.ofSeconds(12)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;
            return Json.read(res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
