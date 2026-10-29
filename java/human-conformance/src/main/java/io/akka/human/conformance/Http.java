package io.akka.human.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Plain HTTP/1.1 JSON calls against one service's base URL. */
final class Http {
  /** An answer; {@code json} is null when the body is empty, NDJSON or not JSON. */
  record Response(int status, String contentType, String body, JsonNode json) {}

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final HttpClient client = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();
  private final String base;

  Http(String baseUrl) {
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  String base() {
    return base;
  }

  Response get(String path) {
    return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
  }

  Response post(String path, JsonNode body) {
    String json = body == null ? "{}" : body.toString();
    return send(HttpRequest.newBuilder(URI.create(base + path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
  }

  /** Encodes one path segment or query value. */
  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private Response send(HttpRequest.Builder builder) {
    HttpRequest request = builder.timeout(REQUEST_TIMEOUT).build();
    try {
      HttpResponse<String> answer = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      String type = answer.headers().firstValue("Content-Type").orElse("");
      return new Response(answer.statusCode(), type, answer.body(), parse(type, answer.body()));
    } catch (IOException e) {
      throw new UncheckedIOException(request.method() + " " + request.uri() + ": " + e, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  private static JsonNode parse(String contentType, String body) {
    if (body == null || body.isBlank() || contentType.contains("ndjson")) return null;
    try {
      return Json.MAPPER.readTree(body);
    } catch (IOException e) {
      return null;
    }
  }
}
