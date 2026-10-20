package org.openmetadata.service.integration;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;

final class DorisHttpHealth {
  private static final int MAX_RESPONSE_BYTES = 4096;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private static final ObjectMapper JSON = new ObjectMapper();

  static {
    JSON.getFactory()
        .setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(10).build());
  }

  private DorisHttpHealth() {}

  static boolean healthy(JdbcEndpoint endpoint) {
    if (endpoint.dialect() != JdbcDialect.Doris || !"DISABLED".equals(endpoint.tlsMode())) {
      return false;
    }
    LimitedBody body = new LimitedBody();
    CompletableFuture<HttpResponse<byte[]>> request = null;
    try {
      URI uri =
          URI.create("http://" + endpoint.host() + ":" + endpoint.dorisHttpPort() + "/api/health");
      HttpRequest health =
          HttpRequest.newBuilder(uri)
              .timeout(REQUEST_TIMEOUT)
              .header("Accept", "application/json")
              .GET()
              .build();
      request = CLIENT.sendAsync(health, ignored -> body);
      HttpResponse<byte[]> response =
          request.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (response.statusCode() != 200) {
        return false;
      }
      JsonNode result = JSON.readTree(response.body());
      return result != null
          && result.isObject()
          && result.path("code").isIntegralNumber()
          && result.path("code").canConvertToInt()
          && result.path("code").intValue() == 0
          && "success".equals(result.path("msg").textValue());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (IOException | ExecutionException | TimeoutException | RuntimeException e) {
      return false;
    } finally {
      body.cancel();
      if (request != null) {
        request.cancel(true);
      }
    }
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private volatile Flow.Subscription subscription;
    private int received;

    @Override
    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
      for (ByteBuffer item : items) {
        if (item.remaining() > MAX_RESPONSE_BYTES - received) {
          cancel();
          delegate.onError(new IOException("Health response exceeded its size limit."));
          return;
        }
        received += item.remaining();
      }
      delegate.onNext(items);
    }

    @Override
    public void onError(Throwable error) {
      delegate.onError(error);
    }

    @Override
    public void onComplete() {
      delegate.onComplete();
    }

    void cancel() {
      Flow.Subscription current = subscription;
      if (current != null) {
        current.cancel();
      }
    }
  }
}
