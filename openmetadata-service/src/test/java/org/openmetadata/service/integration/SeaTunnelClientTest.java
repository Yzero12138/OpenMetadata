package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
              "{\"pipelines\":[{\"latestSavepoint\":{\"status\":\"COMPLETED\",\"checkpointType\":\"savepoint\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    assertEquals("3.0.0", client.version());
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

  private URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }
}
