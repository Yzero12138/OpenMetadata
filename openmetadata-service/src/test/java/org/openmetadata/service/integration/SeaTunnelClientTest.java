package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationCdcModels.CheckpointSummary;

class SeaTunnelClientTest {
  private static final String JOB_ID = "9223372036854775806";
  private static final ObjectMapper JSON = new ObjectMapper();
  private HttpServer server;
  private SeaTunnelClient client;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    client = new SeaTunnelClient(baseUri(), Duration.ofSeconds(2));
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void submitPreservesDecimalStringJobIdAtHttpBoundary() throws Exception {
    AtomicReference<JsonNode> received = new AtomicReference<>();
    AtomicReference<String> query = new AtomicReference<>();
    server.createContext(
        "/submit-job",
        exchange -> {
          received.set(JSON.readTree(exchange.getRequestBody()));
          query.set(exchange.getRequestURI().getRawQuery());
          byte[] response = ("{\"jobId\":\"" + JOB_ID + "\"}").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    client.submit(JOB_ID, "synthetic-copy", Map.of("env", Map.of("job.mode", "BATCH")), false);
    assertEquals(
        "jobId=" + JOB_ID + "&jobName=synthetic-copy&isStartWithSavePoint=false", query.get());
    assertFalse(received.get().has("params"));
  }

  @Test
  void infoExposesOnlyWhitelistedStatusAndRealCounters() throws Exception {
    server.createContext(
        "/job-info/" + JOB_ID,
        exchange -> {
          byte[] response =
              ("{\"jobId\":\""
                      + JOB_ID
                      + "\",\"jobStatus\":\"RUNNING\",\"metrics\":{\"SourceReceivedCount\":7,"
                      + "\"SinkWriteCount\":5},\"jobConfig\":{\"password\":\"private-secret\"},"
                      + "\"errorMsg\":\"jdbc:postgresql://internal password=private-secret\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    SeaTunnelClient.JobSnapshot result = client.info(JOB_ID);
    assertEquals("RUNNING", result.status());
    assertEquals(7L, result.sourceReceivedCount());
    assertEquals(5L, result.sinkWriteCount());
    String serialized = JSON.writeValueAsString(result);
    assertFalse(serialized.contains("private-secret"));
    assertFalse(serialized.contains("jdbc:"));
    assertFalse(serialized.contains("jobConfig"));
  }

  @Test
  void missingMetricsStayUnavailable() throws Exception {
    server.createContext(
        "/job-info/" + JOB_ID,
        exchange -> {
          byte[] response = "{\"jobStatus\":\"FINISHED\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    SeaTunnelClient.JobSnapshot result = client.info(JOB_ID);
    assertNull(result.sourceReceivedCount());
    assertNull(result.sinkWriteCount());
  }

  @Test
  void savepointInProgressRemainsStoppingUntilTheEngineConfirmsCompletion() {
    AtomicReference<String> status = new AtomicReference<>("DOING_SAVEPOINT");
    server.createContext(
        "/job-info/" + JOB_ID,
        exchange -> {
          byte[] response =
              ("{\"jobId\":\"" + JOB_ID + "\",\"jobStatus\":\"" + status.get() + "\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    assertEquals("STOPPING", client.info(JOB_ID).status());
    status.set("SAVEPOINT_DONE");
    assertEquals("SAVEPOINT_DONE", client.info(JOB_ID).status());
    status.set("UNRECOGNIZED_STATE");
    assertEquals(
        "ENGINE_UNAVAILABLE",
        assertThrows(IntegrationException.class, () -> client.info(JOB_ID)).code());
  }

  @Test
  void readsRealOverviewVersionAndRequiresCompletedSavepointEvidence() {
    server.createContext(
        "/overview",
        exchange -> {
          byte[] response =
              "{\"projectVersion\":\"3.0.0\",\"workers\":\"1\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.createContext(
        "/jobs/checkpoints/" + JOB_ID,
        exchange -> {
          byte[] response =
              ("{\"jobId\":\""
                      + JOB_ID
                      + "\",\"pipelines\":[{\"latestSavepoint\":{\"status\":\"COMPLETED\",\"checkpointType\":\"savepoint\",\"completedTimestamp\":1234}}]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    assertEquals("3.0.0", client.version());
    assertTrue(client.hasCompletedSavepoint(JOB_ID));
  }

  @Test
  void resumeEvidenceRequiresExactJobIdentityAndCompletedSavepointTypeAndTimestamp() {
    var response = new AtomicReference<String>();
    server.createContext(
        "/jobs/checkpoints/" + JOB_ID,
        exchange -> {
          byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    String valid =
        "{\"jobId\":\""
            + JOB_ID
            + "\",\"pipelines\":[{\"latestSavepoint\":{\"status\":\"COMPLETED\",\"checkpointType\":\"savepoint\",\"completedTimestamp\":1234}}]}";
    for (String malformed :
        new String[] {
          valid.replace(JOB_ID, "12345"),
          valid.replace("\"jobId\":\"" + JOB_ID + "\",", ""),
          valid.replace("\"" + JOB_ID + "\"", JOB_ID),
          valid.replace("\"savepoint\"", "\"checkpoint\""),
          valid.replace("COMPLETED", "IN_PROGRESS"),
          valid.replace("1234", "-1"),
          valid.replace(",\"completedTimestamp\":1234", "")
        }) {
      response.set(malformed);
      assertFalse(client.hasCompletedSavepoint(JOB_ID), malformed);
    }
    response.set(valid);
    assertTrue(client.hasCompletedSavepoint(JOB_ID));
  }

  @Test
  void treatsVersionThreeEchoOnlyJobInfoAsAuthoritativeMissingJob() {
    server.createContext(
        "/job-info/" + JOB_ID,
        exchange -> {
          byte[] response = ("{\"jobId\":\"" + JOB_ID + "\"}").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    IntegrationException failure =
        assertThrows(IntegrationException.class, () -> client.info(JOB_ID));
    assertEquals("JOB_NOT_FOUND", failure.code());
  }

  @Test
  void timedOutSubmissionIsUncertainAndNeverExposesRequestSecrets() {
    server.createContext(
        "/submit-job",
        exchange -> {
          try {
            Thread.sleep(250);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    SeaTunnelClient shortTimeout = new SeaTunnelClient(baseUri(), Duration.ofMillis(40));
    IntegrationException failure =
        assertThrows(
            IntegrationException.class,
            () ->
                shortTimeout.submit(
                    JOB_ID, "synthetic-copy", Map.of("password", "private-secret"), false));
    assertEquals("SUBMISSION_UNKNOWN", failure.code());
    assertFalse(failure.getMessage().contains("private-secret"));
  }

  @Test
  void rejectsNoncanonicalJobIdsBeforeCallingEngine() {
    for (String value :
        new String[] {"1e18", "-1", "1/stop-job", "1?x=2", "01", "9223372036854775808"}) {
      assertThrows(IntegrationException.class, () -> client.info(value));
    }
  }

  @Test
  void oversizedErrorBodiesAreBoundedAndRedacted() {
    server.createContext(
        "/job-info/" + JOB_ID,
        exchange -> {
          byte[] response =
              ("password=private-secret".repeat(100000)).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(500, response.length);
          try {
            exchange.getResponseBody().write(response);
          } finally {
            exchange.close();
          }
        });
    IntegrationException failure =
        assertThrows(IntegrationException.class, () -> client.info(JOB_ID));
    assertEquals("ENGINE_UNAVAILABLE", failure.code());
    assertFalse(failure.getMessage().contains("private-secret"));
  }

  @Test
  void discoversExactSourceFactoryAtTheConfiguredBasePrefix() {
    AtomicReference<String> request = new AtomicReference<>();
    server.createContext(
        "/engine/option-rules",
        exchange -> {
          request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
          byte[] response = sourceOptions("Oracle-CDC").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    SeaTunnelClient prefixed =
        new SeaTunnelClient(URI.create(baseUri() + "/engine///"), Duration.ofSeconds(2));
    assertTrue(prefixed.hasSourceConnector("Oracle-CDC"));
    assertEquals("GET /engine/option-rules?type=source&plugin=Oracle-CDC", request.get());
  }

  @Test
  void onlyStructuredMissingSourcePluginIsConclusiveAbsence() {
    respond(
        "/option-rules",
        404,
        "{\"status\":\"FAIL\",\"message\":\"Plugin 'Oracle-CDC' not found for type 'source'.\"}");
    assertFalse(client.hasSourceConnector("Oracle-CDC"));
  }

  @Test
  void rejectsUnsafeSourcePluginNamesBeforeAnyHttpRequest() {
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/option-rules",
        exchange -> {
          requests.incrementAndGet();
          exchange.close();
        });
    for (String plugin :
        new String[] {
          null,
          "",
          " Oracle-CDC",
          "Oracle CDC",
          "../Jdbc",
          "Jdbc?x=1",
          "Jdbc&x=1",
          "Jdbc%26x",
          "Jdbc\n",
          "数据库",
          "x".repeat(65)
        }) {
      IntegrationException failure =
          assertThrows(IntegrationException.class, () -> client.hasSourceConnector(plugin));
      assertEquals("INVALID_CONFIGURATION", failure.code());
    }
    assertEquals(0, requests.get());
  }

  @Test
  void sourceProbeRejectsMalformedOrMismatchedFactoryMetadata() {
    AtomicReference<String> payload = new AtomicReference<>();
    respond("/option-rules", 200, payload);
    for (String response :
        new String[] {
          "",
          "null",
          "[]",
          "{}",
          "private-secret",
          sourceOptions("Postgres-CDC"),
          sourceOptions("Oracle-CDC").replace("seatunnel", "flink"),
          sourceOptions("Oracle-CDC").replace("\"source\"", "\"sink\""),
          sourceOptions("Oracle-CDC").replace("\"optionRule\":{}", "\"optionRule\":[]"),
          sourceOptions("Oracle-CDC") + " {}"
        }) {
      payload.set(response);
      assertEngineUnavailable(() -> client.hasSourceConnector("Oracle-CDC"));
    }
  }

  @Test
  void sourceProbeRejectsRouteAuthAndMalformedNotFoundResponses() {
    AtomicReference<String> payload = new AtomicReference<>();
    respond("/option-rules", 404, payload);
    for (String response :
        new String[] {
          "<html>private-secret</html>",
          "{}",
          "null",
          "",
          "{\"status\":\"FAIL\",\"message\":\"Not authenticated private-secret\"}",
          "{\"status\":\"FAIL\",\"message\":\"Plugin 'Jdbc' not found for type 'source'.\"}",
          "{\"status\":\"FAIL\",\"message\":\"Plugin 'Oracle-CDC' not found for type 'sink'.\"}",
          "{\"status\":\"OK\",\"message\":\"Plugin 'Oracle-CDC' not found for type 'source'.\"}"
        }) {
      payload.set(response);
      assertEngineUnavailable(() -> client.hasSourceConnector("Oracle-CDC"));
    }
  }

  @Test
  void sourceProbeDoesNotFollowRedirectsOrExposeEngineErrors() {
    AtomicInteger redirected = new AtomicInteger();
    AtomicInteger status = new AtomicInteger(302);
    server.createContext(
        "/option-rules",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/private");
          byte[] response = "private-secret".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status.get(), response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.createContext(
        "/private",
        exchange -> {
          redirected.incrementAndGet();
          exchange.close();
        });
    for (int code : new int[] {302, 307, 400, 401, 403, 429, 500}) {
      status.set(code);
      assertEngineUnavailable(() -> client.hasSourceConnector("Oracle-CDC"));
    }
    assertEquals(0, redirected.get());
  }

  @Test
  void sourceProbeBoundsSuccessfulAndMissingPluginBodies() {
    AtomicInteger status = new AtomicInteger(200);
    server.createContext(
        "/option-rules",
        exchange -> {
          byte[] response = ("private-secret".repeat(100000)).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status.get(), response.length);
          try {
            exchange.getResponseBody().write(response);
          } catch (IOException ignored) {
          } finally {
            exchange.close();
          }
        });
    assertEngineUnavailable(() -> client.hasSourceConnector("Oracle-CDC"));
    status.set(404);
    assertEngineUnavailable(() -> client.hasSourceConnector("Oracle-CDC"));
  }

  @Test
  void checkpointsSumExplicitPipelineCountersAndWhitelistCompletedTimestamps() throws Exception {
    respond(
        "/jobs/checkpoints/" + JOB_ID,
        200,
        """
        {"jobId":"%s","updatedAt":1700000000000,"pipelines":[
          {"counts":{"triggered":9,"completed":4,"failed":4,"restored":3},
           "latestCompleted":{"status":"COMPLETED","completedTimestamp":1699999998000,
                              "failureReason":"private-secret","statePath":"/private/state"},
           "latestSavepoint":{"status":"COMPLETED","checkpointType":"savepoint",
                              "completedTimestamp":1699999997000},
           "history":[{"password":"private-secret"}],"jobConfig":{"password":"private-secret"}},
          {"counts":{"triggered":2,"completed":1,"failed":1,"restored":1},
           "latestCompleted":{"status":"COMPLETED","completedTimestamp":1699999999000},
           "latestSavepoint":{"status":"FAILED","completedTimestamp":1700000000001,
                              "failureReason":"private-secret"}}
        ]}
        """
            .formatted(JOB_ID));
    CheckpointSummary result = client.checkpoints(JOB_ID);
    assertEquals(
        new CheckpointSummary(11, 5, 5, 4, 1699999999000L, 1699999997000L, 1700000000000L), result);
    assertEquals(result, client.checkpoints(JOB_ID));
    JsonNode serialized = JSON.valueToTree(result);
    assertEquals(7, serialized.size());
    assertFalse(serialized.toString().contains("private-secret"));
    assertFalse(serialized.toString().contains("history"));
    assertFalse(serialized.toString().contains("statePath"));
    assertFalse(serialized.toString().contains("jobConfig"));
  }

  @Test
  void checkpointZeroCountersRequireExplicitEngineDataAndMissingTimesStayNull() {
    respond("/jobs/checkpoints/" + JOB_ID, 200, checkpointPayload("0", "0", "0", "0"));
    assertEquals(new CheckpointSummary(0, 0, 0, 0, null, null, null), client.checkpoints(JOB_ID));
  }

  @Test
  void checkpointSummaryRequiresTheRequestedJobIdentityEvenWithValidCounters() {
    respond(
        "/jobs/checkpoints/" + JOB_ID,
        200,
        checkpointPayload("1", "1", "0", "0").replace("\"jobId\":\"" + JOB_ID + "\",", ""));
    assertEngineUnavailable(() -> client.checkpoints(JOB_ID));
  }

  @Test
  void checkpointSummaryRejectsNontextualOrMismatchedJobIdentityWithValidCounters() {
    AtomicReference<String> payload = new AtomicReference<>();
    respond("/jobs/checkpoints/" + JOB_ID, 200, payload);
    for (String identity : new String[] {JOB_ID, "null", "true", "[]", "{}", "\"123\""}) {
      payload.set(
          checkpointPayload("1", "1", "0", "0")
              .replace("\"jobId\":\"" + JOB_ID + "\"", "\"jobId\":" + identity));
      assertEngineUnavailable(() -> client.checkpoints(JOB_ID));
    }
  }

  @Test
  void checkpointSummaryRequiresSavepointTypeBeforeExposingItsCompletedTimestamp() {
    AtomicReference<String> payload = new AtomicReference<>();
    respond("/jobs/checkpoints/" + JOB_ID, 200, payload);
    for (String type : new String[] {"\"checkpoint\"", "\"\"", "null", "1", "{}"}) {
      payload.set(
          checkpointPayload("1", "1", "0", "0")
              .replace(
                  "\"counts\":",
                  "\"latestSavepoint\":{\"status\":\"COMPLETED\",\"completedTimestamp\":100,"
                      + "\"checkpointType\":"
                      + type
                      + "},\"counts\":"));
      assertNull(client.checkpoints(JOB_ID).latestSavepointAt());
    }
    payload.set(
        checkpointPayload("1", "1", "0", "0")
            .replace(
                "\"counts\":",
                "\"latestSavepoint\":{\"status\":\"COMPLETED\",\"completedTimestamp\":100},\"counts\":"));
    assertNull(client.checkpoints(JOB_ID).latestSavepointAt());
    payload.set(
        checkpointPayload("1", "1", "0", "0")
            .replace(
                "\"counts\":",
                "\"latestSavepoint\":{\"status\":\"COMPLETED\",\"completedTimestamp\":100,"
                    + "\"checkpointType\":\"SAVEPOINT\"},\"counts\":"));
    assertEquals(100L, client.checkpoints(JOB_ID).latestSavepointAt());
  }

  @Test
  void checkpointSummaryIgnoresNonCompletedAndTriggerTimes() {
    respond(
        "/jobs/checkpoints/" + JOB_ID,
        200,
        checkpointPayload("3", "0", "3", "2")
            .replace(
                "\"restored\":2}",
                "\"restored\":2},\"latestCompleted\":{\"status\":\"FAILED\","
                    + "\"completedTimestamp\":100,\"triggerTimestamp\":90},"
                    + "\"latestSavepoint\":{\"status\":\"IN_PROGRESS\",\"triggerTimestamp\":200}"));
    assertEquals(new CheckpointSummary(3, 0, 3, 2, null, null, null), client.checkpoints(JOB_ID));
  }

  @Test
  void checkpointSummaryRejectsMissingCountsMalformedPayloadsAndWrongJobIdentity() {
    AtomicReference<String> payload = new AtomicReference<>();
    respond("/jobs/checkpoints/" + JOB_ID, 200, payload);
    for (String response :
        new String[] {
          "",
          "null",
          "[]",
          "{}",
          "{\"jobId\":\"" + JOB_ID + "\"}",
          "{\"pipelines\":[]}",
          "{\"pipelines\":{}}",
          "{\"pipelines\":[{}]}",
          checkpointPayload("1", "0", "1", "0").replace("\"triggered\":1,", ""),
          checkpointPayload("1", "0", "1", "0").replace("\"completed\":0,", ""),
          checkpointPayload("1", "0", "1", "0").replace("\"failed\":1,", ""),
          checkpointPayload("1", "0", "1", "0").replace("\"restored\":0", "\"inProgress\":0"),
          checkpointPayload("1", "0", "1", "0").replace(JOB_ID, "123"),
          checkpointPayload("1", "0", "1", "0") + " {}"
        }) {
      payload.set(response);
      assertEngineUnavailable(() -> client.checkpoints(JOB_ID));
    }
  }

  @Test
  void checkpointSummaryRejectsNegativeFractionalTextualAndOutOfRangeCounts() {
    AtomicReference<String> payload = new AtomicReference<>();
    respond("/jobs/checkpoints/" + JOB_ID, 200, payload);
    for (String value :
        new String[] {"-1", "1.5", "\"1\"", "null", "true", "9223372036854775808"}) {
      for (int field = 0; field < 4; field++) {
        String[] counts = {"0", "0", "0", "0"};
        counts[field] = value;
        payload.set(checkpointPayload(counts[0], counts[1], counts[2], counts[3]));
        assertEngineUnavailable(() -> client.checkpoints(JOB_ID));
      }
    }
  }

  @Test
  void checkpointSummaryRejectsOverflowWhileSummingEveryCounter() {
    AtomicReference<String> payload = new AtomicReference<>();
    respond("/jobs/checkpoints/" + JOB_ID, 200, payload);
    for (int field = 0; field < 4; field++) {
      String[] counts = {"0", "0", "0", "0"};
      counts[field] = "9223372036854775807";
      payload.set(
          checkpointPayload(counts[0], counts[1], counts[2], counts[3])
              .replace("]}", "," + checkpointPipeline("1", "1", "1", "1") + "]}"));
      assertEngineUnavailable(() -> client.checkpoints(JOB_ID));
    }
  }

  @Test
  void checkpointSummaryRejectsMalformedCompletedAndUpdatedTimestamps() {
    AtomicReference<String> payload = new AtomicReference<>();
    respond("/jobs/checkpoints/" + JOB_ID, 200, payload);
    for (String timestamp : new String[] {"-1", "1.5", "\"100\"", "9223372036854775808"}) {
      for (String latest : new String[] {"latestCompleted", "latestSavepoint"}) {
        payload.set(
            checkpointPayload("1", "1", "0", "0")
                .replace(
                    "\"counts\":",
                    "\""
                        + latest
                        + "\":{\"status\":\"COMPLETED\",\"completedTimestamp\":"
                        + timestamp
                        + "},\"counts\":"));
        assertEngineUnavailable(() -> client.checkpoints(JOB_ID));
      }
      payload.set(
          checkpointPayload("0", "0", "0", "0")
              .replace("\"pipelines\":", "\"updatedAt\":" + timestamp + ",\"pipelines\":"));
      assertEngineUnavailable(() -> client.checkpoints(JOB_ID));
    }
    payload.set(
        checkpointPayload("1", "1", "0", "0")
            .replace("\"counts\":", "\"latestCompleted\":{\"status\":\"COMPLETED\"},\"counts\":"));
    assertEngineUnavailable(() -> client.checkpoints(JOB_ID));
  }

  @Test
  void checkpointsRespectTheCallersRefreshTimeoutBudget() {
    server.createContext(
        "/jobs/checkpoints/" + JOB_ID,
        exchange -> {
          try {
            Thread.sleep(400);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    long started = System.nanoTime();
    assertEngineUnavailable(() -> client.checkpoints(JOB_ID, Duration.ofMillis(40)));
    assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(350)) < 0);
  }

  @Test
  void checkpointsRejectUnsafeJobIdsBeforeCallingEngine() {
    for (String value : new String[] {null, "0", "01", "1?x=2", "1/other", "9223372036854775808"}) {
      IntegrationException failure =
          assertThrows(IntegrationException.class, () -> client.checkpoints(value));
      assertEquals("INVALID_CONFIGURATION", failure.code());
    }
  }

  private void respond(String path, int status, String payload) {
    respond(path, status, new AtomicReference<>(payload));
  }

  private void respond(String path, int status, AtomicReference<String> payload) {
    server.createContext(
        path,
        exchange -> {
          byte[] response = payload.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
  }

  private static String sourceOptions(String plugin) {
    return "{\"engineType\":\"seatunnel\",\"pluginType\":\"source\",\"pluginName\":\""
        + plugin
        + "\",\"optionRule\":{}}";
  }

  private static String checkpointPayload(
      String triggered, String completed, String failed, String restored) {
    return "{\"jobId\":\""
        + JOB_ID
        + "\",\"pipelines\":["
        + checkpointPipeline(triggered, completed, failed, restored)
        + "]}";
  }

  private static String checkpointPipeline(
      String triggered, String completed, String failed, String restored) {
    return "{\"counts\":{\"triggered\":"
        + triggered
        + ",\"completed\":"
        + completed
        + ",\"failed\":"
        + failed
        + ",\"restored\":"
        + restored
        + "}}";
  }

  private static void assertEngineUnavailable(Runnable operation) {
    IntegrationException failure = assertThrows(IntegrationException.class, operation::run);
    assertEquals("ENGINE_UNAVAILABLE", failure.code());
    assertEquals(503, failure.status());
    assertEquals("The integration engine is unavailable.", failure.getMessage());
    assertNull(failure.getCause());
  }

  private URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }
}
