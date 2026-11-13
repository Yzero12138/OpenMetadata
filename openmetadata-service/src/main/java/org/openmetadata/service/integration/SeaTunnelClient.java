package org.openmetadata.service.integration;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.openmetadata.service.integration.IntegrationCdcModels.CheckpointSummary;

public final class SeaTunnelClient {
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> STATUSES =
      Set.of(
          "CREATED",
          "INITIALIZING",
          "SCHEDULED",
          "RUNNING",
          "FINISHED",
          "FAILING",
          "FAILED",
          "CANCELING",
          "CANCELLING",
          "CANCELED",
          "CANCELLED",
          "STOPPING",
          "STOPPED",
          "SUSPENDED",
          "RESTARTING",
          "SAVEPOINT_DONE");

  static {
    JSON.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    JSON.getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(40).maxStringLength(262144).build());
  }

  private final URI baseUri;
  private final Duration timeout;
  private final HttpClient http;

  public SeaTunnelClient(URI baseUri, Duration timeout) {
    this.baseUri = baseUri;
    this.timeout = timeout;
    http =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public String version() {
    JsonNode response = request("GET", "/overview", null, false);
    String version = response.path("projectVersion").asText("");
    if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-a-zA-Z0-9.]+)?")) {
      throw new IntegrationException("ENGINE_UNAVAILABLE", 503);
    }
    return version;
  }

  public boolean hasSourceConnector(String plugin) {
    if (plugin == null || !plugin.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}")) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    JsonNode response =
        request(
            "GET",
            "/option-rules?type="
                + URLEncoder.encode("source", StandardCharsets.UTF_8)
                + "&plugin="
                + URLEncoder.encode(plugin, StandardCharsets.UTF_8),
            null,
            false,
            timeout,
            plugin);
    if (response == null) {
      return false;
    }
    if (!response.isObject()
        || !"seatunnel".equals(response.path("engineType").asText())
        || !"source".equals(response.path("pluginType").asText())
        || !plugin.equals(response.path("pluginName").asText())
        || !response.path("optionRule").isObject()) {
      throw new IntegrationException("ENGINE_UNAVAILABLE", 503);
    }
    return true;
  }

  public CheckpointSummary checkpoints(String jobId) {
    return checkpoints(jobId, timeout);
  }

  CheckpointSummary checkpoints(String jobId, Duration requestTimeout) {
    requireJobId(jobId);
    JsonNode response = request("GET", "/jobs/checkpoints/" + jobId, null, false, requestTimeout);
    JsonNode pipelines = response.path("pipelines");
    if (!response.isObject()
        || !response.path("jobId").isTextual()
        || !jobId.equals(response.path("jobId").textValue())
        || !pipelines.isArray()
        || pipelines.isEmpty()) {
      throw new IntegrationException("ENGINE_UNAVAILABLE", 503);
    }
    long triggered = 0;
    long completed = 0;
    long failed = 0;
    long restored = 0;
    Long latestCompletedAt = null;
    Long latestSavepointAt = null;
    try {
      for (JsonNode pipeline : pipelines) {
        JsonNode counts = pipeline.path("counts");
        triggered = Math.addExact(triggered, nonnegativeLong(counts.path("triggered")));
        completed = Math.addExact(completed, nonnegativeLong(counts.path("completed")));
        failed = Math.addExact(failed, nonnegativeLong(counts.path("failed")));
        restored = Math.addExact(restored, nonnegativeLong(counts.path("restored")));
        latestCompletedAt =
            latest(latestCompletedAt, completedAt(pipeline.path("latestCompleted")));
        JsonNode savepoint = pipeline.path("latestSavepoint");
        Long savepointCompletedAt = completedAt(savepoint);
        if ("savepoint".equalsIgnoreCase(savepoint.path("checkpointType").asText())) {
          latestSavepointAt = latest(latestSavepointAt, savepointCompletedAt);
        }
      }
    } catch (ArithmeticException e) {
      throw new IntegrationException("ENGINE_UNAVAILABLE", 503);
    }
    return new CheckpointSummary(
        triggered,
        completed,
        failed,
        restored,
        latestCompletedAt,
        latestSavepointAt,
        optionalTimestamp(response.path("updatedAt")));
  }

  private static long nonnegativeLong(JsonNode value) {
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
      throw new IntegrationException("ENGINE_UNAVAILABLE", 503);
    }
    return value.longValue();
  }

  private static Long optionalTimestamp(JsonNode value) {
    return value.isMissingNode() || value.isNull() ? null : nonnegativeLong(value);
  }

  private static Long completedAt(JsonNode checkpoint) {
    if (checkpoint.isMissingNode() || checkpoint.isNull()) {
      return null;
    }
    if (!checkpoint.isObject()) {
      throw new IntegrationException("ENGINE_UNAVAILABLE", 503);
    }
    return "COMPLETED".equals(checkpoint.path("status").asText())
        ? nonnegativeLong(checkpoint.path("completedTimestamp"))
        : null;
  }

  private static Long latest(Long previous, Long candidate) {
    if (previous == null) {
      return candidate;
    }
    return candidate == null ? previous : Long.valueOf(Math.max(previous, candidate));
  }

  public void submit(
      String jobId, String jobName, Map<String, Object> configuration, boolean resume) {
    requireJobId(jobId);
    String path =
        "/submit-job?jobId="
            + jobId
            + "&jobName="
            + URLEncoder.encode(jobName, StandardCharsets.UTF_8)
            + "&isStartWithSavePoint="
            + resume;
    JsonNode response = request("POST", path, configuration, true);
    JsonNode acknowledgement =
        response.isArray() && !response.isEmpty() ? response.get(0) : response;
    if (!jobId.equals(acknowledgement.path("jobId").asText())) {
      throw new IntegrationException("SUBMISSION_UNKNOWN", 503);
    }
  }

  public JobSnapshot info(String jobId) {
    return info(jobId, timeout);
  }

  JobSnapshot info(String jobId, Duration requestTimeout) {
    requireJobId(jobId);
    JsonNode response = request("GET", "/job-info/" + jobId, null, false, requestTimeout);
    JsonNode job =
        response.has("data") && response.path("data").isObject() ? response.path("data") : response;
    if (job.has("jobId") && !jobId.equals(job.path("jobId").asText())) {
      throw new IntegrationException("ENGINE_UNAVAILABLE", 503);
    }
    if (job.size() == 1 && jobId.equals(job.path("jobId").asText()) && !job.has("jobStatus")) {
      throw new IntegrationException("JOB_NOT_FOUND", 404);
    }
    String status = job.path("jobStatus").asText("");
    if ("DOING_SAVEPOINT".equals(status)) {
      status = "STOPPING";
    }
    if (!STATUSES.contains(status)) {
      throw new IntegrationException("ENGINE_UNAVAILABLE", 503);
    }
    JsonNode metrics = job.path("metrics");
    Long sourceCount = count(metrics, "SourceReceivedCount", "sourceReceivedCount");
    Long sinkCount = count(metrics, "SinkWriteCount", "sinkWriteCount");
    if (sourceCount == null) {
      sourceCount = count(job, "SourceReceivedCount", "sourceReceivedCount");
    }
    if (sinkCount == null) {
      sinkCount = count(job, "SinkWriteCount", "sinkWriteCount");
    }
    return new JobSnapshot(status, sourceCount, sinkCount);
  }

  public void stop(String jobId, boolean savepoint) {
    requireJobId(jobId);
    JsonNode response =
        request(
            "POST", "/stop-job", Map.of("jobId", jobId, "isStopWithSavePoint", savepoint), false);
    if (response.has("success") && !response.path("success").asBoolean()) {
      throw new IntegrationException("ENGINE_UNAVAILABLE", 503);
    }
  }

  public boolean hasCompletedSavepoint(String jobId) {
    return hasCompletedSavepoint(jobId, timeout);
  }

  boolean hasCompletedSavepoint(String jobId, Duration requestTimeout) {
    requireJobId(jobId);
    JsonNode response = request("GET", "/jobs/checkpoints/" + jobId, null, false, requestTimeout);
    JsonNode pipelines = response.path("pipelines");
    if (!pipelines.isArray() || pipelines.isEmpty()) {
      return false;
    }
    for (JsonNode pipeline : pipelines) {
      JsonNode savepoint = pipeline.path("latestSavepoint");
      if (!"COMPLETED".equals(savepoint.path("status").asText())
          || !"savepoint".equalsIgnoreCase(savepoint.path("checkpointType").asText())) {
        return false;
      }
    }
    return true;
  }

  public static void requireJobId(String jobId) {
    if (jobId == null || !jobId.matches("[1-9][0-9]{0,18}")) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
    try {
      if (Long.parseLong(jobId) <= 0) {
        throw new NumberFormatException();
      }
    } catch (NumberFormatException e) {
      throw new IntegrationException("INVALID_CONFIGURATION", 400);
    }
  }

  private JsonNode request(
      String method, String path, Map<String, Object> body, boolean submission) {
    return request(method, path, body, submission, timeout);
  }

  private JsonNode request(
      String method,
      String path,
      Map<String, Object> body,
      boolean submission,
      Duration requestTimeout) {
    return request(method, path, body, submission, requestTimeout, null);
  }

  private JsonNode request(
      String method,
      String path,
      Map<String, Object> body,
      boolean submission,
      Duration requestTimeout,
      String missingSourcePlugin) {
    String error = submission ? "SUBMISSION_UNKNOWN" : "ENGINE_UNAVAILABLE";
    CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      String base = baseUri.toString().replaceAll("/+$", "");
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(requestTimeout)
              .header("Accept", "application/json");
      if (body == null) {
        builder.GET();
      } else {
        byte[] serialized = JSON.writeValueAsBytes(body);
        if (serialized.length > MAX_RESPONSE_BYTES) {
          throw new IntegrationException("INVALID_CONFIGURATION", 400);
        }
        builder
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofByteArray(serialized));
      }
      pending = http.sendAsync(builder.build(), response -> new BoundedSubscriber());
      HttpResponse<byte[]> response = pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (response.statusCode() == 404 && !submission) {
        if (missingSourcePlugin != null) {
          JsonNode missing = JSON.readTree(response.body());
          if (missing != null
              && missing.isObject()
              && "FAIL".equals(missing.path("status").asText())
              && ("Plugin '" + missingSourcePlugin + "' not found for type 'source'.")
                  .equals(missing.path("message").asText())) {
            return null;
          }
          throw new IntegrationException(error, 503);
        }
        throw new IntegrationException("JOB_NOT_FOUND", 404);
      }
      if (response.statusCode() < 200
          || response.statusCode() >= 300
          || (missingSourcePlugin != null && response.statusCode() != 200)) {
        if (submission
            && response.statusCode() >= 400
            && response.statusCode() < 500
            && response.statusCode() != 408
            && response.statusCode() != 409
            && response.statusCode() != 429) {
          throw new IntegrationException("INVALID_CONFIGURATION", 400);
        }
        throw new IntegrationException(error, 503);
      }
      JsonNode parsed =
          response.body().length == 0 ? JSON.createObjectNode() : JSON.readTree(response.body());
      return parsed == null ? JSON.nullNode() : parsed;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IntegrationException(error, 503);
    } catch (IOException | ExecutionException | TimeoutException | IllegalArgumentException e) {
      throw new IntegrationException(error, 503);
    } finally {
      if (pending != null && !pending.isDone()) {
        pending.cancel(true);
      }
    }
  }

  private Long count(JsonNode metrics, String canonical, String alternative) {
    JsonNode value = metrics.has(canonical) ? metrics.path(canonical) : metrics.path(alternative);
    if (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0) {
      return value.longValue();
    }
    if (value.isTextual() && value.textValue().matches("[0-9]{1,19}")) {
      try {
        return Long.parseLong(value.textValue());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  public record JobSnapshot(String status, Long sourceReceivedCount, Long sinkWriteCount) {}

  private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(new IOException("Engine response limit exceeded"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
      result.completeExceptionally(new IOException("Engine response could not be read"));
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
