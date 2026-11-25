package io.github.abdullah204.reliablesync.acme;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.abdullah204.foundations.http.ResilientHttpClient;
import io.github.abdullah204.foundations.sync.Page;
import io.github.abdullah204.foundations.sync.SourceRecord;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Thin, typed client for Acme's list endpoints. Retries, backoff and Retry-After are delegated to
 * {@link ResilientHttpClient}. This class only knows URLs, auth and response shape.
 */
public final class AcmeApiClient {

    private final URI baseUri;
    private final String apiKey;
    private final int pageSize;
    private final ResilientHttpClient http;
    private final ObjectMapper mapper;

    public AcmeApiClient(URI baseUri, String apiKey, int pageSize, ResilientHttpClient http, ObjectMapper mapper) {
        this.baseUri = Objects.requireNonNull(baseUri);
        this.apiKey = Objects.requireNonNull(apiKey);
        this.pageSize = pageSize;
        this.http = Objects.requireNonNull(http);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public <T extends SourceRecord> Page<T> list(String resource, Class<T> type, Instant updatedSince, String cursor) {
        StringBuilder url = new StringBuilder(baseUri.toString()).append("/v1/").append(resource)
                .append("?limit=").append(pageSize);
        if (updatedSince != null) {
            url.append("&updated_since=").append(encode(updatedSince.toString()));
        }
        if (cursor != null) {
            url.append("&cursor=").append(encode(cursor));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        String body = http.send(request).body();
        try {
            JsonNode root = mapper.readTree(body);
            List<T> records = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                records.add(mapper.treeToValue(item, type));
            }
            JsonNode next = root.path("next_cursor");
            return new Page<>(records, next.isTextual() ? next.asText() : null);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unexpected response shape from " + request.uri(), e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
