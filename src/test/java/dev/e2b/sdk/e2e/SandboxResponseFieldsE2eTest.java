package dev.e2b.sdk.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.e2b.sdk.Sandbox;
import dev.e2b.sdk.client.ConnectionConfig;
import dev.e2b.sdk.codeinterpreter.CodeInterpreter;
import dev.e2b.sdk.model.NewSandbox;
import dev.e2b.sdk.model.SandboxInfo;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real control-plane regression; creates one short-lived sandbox and always attempts cleanup. */
class SandboxResponseFieldsE2eTest extends E2eTestBase {
    private static final String[] FIELDS = {"templateID", "clientID", "envdVersion"};

    @Test
    void responseFieldsMatchWireWithoutImplicitLookup() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> requests = new ArrayList<>();
        List<Map<String, String>> snapshots = new ArrayList<>();
        String[] createdId = {null};
        OkHttpClient http = new OkHttpClient.Builder()
                .addNetworkInterceptor(chain -> {
                    String method = chain.request().method();
                    String path = chain.request().url().encodedPath();
                    requests.add(method + " " + path);
                    Response response = chain.proceed(chain.request());
                    // Only safe metadata is printed; never log headers, credentials or full bodies.
                    System.out.println("WIRE " + method + " " + path + " status=" + response.code()
                            + " requestId=" + response.header("x-fc-request-id",
                            response.header("x-request-id", "unavailable")));
                    if (response.isSuccessful() && "POST".equals(method)
                            && ("/sandboxes".equals(path) || path.endsWith("/connect"))) {
                        JsonNode body = mapper.readTree(response.peekBody(1024 * 1024).string());
                        if ("/sandboxes".equals(path)) {
                            createdId[0] = body.path("sandboxID").asText(null);
                        }
                        Map<String, String> fields = new LinkedHashMap<>();
                        for (String field : FIELDS) {
                            if (body.has(field)) {
                                fields.put(field, body.get(field).isNull() ? null : body.get(field).asText());
                            }
                        }
                        snapshots.add(fields);
                    }
                    return response;
                }).build();
        ConnectionConfig connection = ConnectionConfig.builder()
                .apiKey(config.getApiKey()).apiUrl(config.getApiUrl()).domain(config.getDomain())
                .requestTimeout(120.0).httpClient(http).build();
        Sandbox sandbox = null;
        String testRun = UUID.randomUUID().toString();
        try {
            System.out.println("TARGET api=" + connection.resolvedApiUrl()
                    + " template=" + config.getTemplate() + " testRun=" + testRun);
            sandbox = Sandbox.create(config.getTemplate(), connection,
                    NewSandbox.builder().timeout(120).autoPause(false)
                            .metadata(Collections.singletonMap("java-sdk-response-fields-e2e", testRun)).build());
            assertEquals(1, requests.size(), "Create must issue only one POST");
            assertEquals("POST /sandboxes", requests.get(0));
            Map<String, String> original = snapshots.get(0);
            assertFields(sandbox, original);
            assertFields(sandbox, original);
            assertEquals(1, requests.size(), "Create getters must not issue requests");
            System.out.println("CREATE_FIELDS_MATCH requests=1 templateId=" + sandbox.getTemplateId()
                    + " envdVersion=" + sandbox.getEnvdVersion()
                    + " templateIdPresent=" + original.containsKey("templateID")
                    + " envdVersionPresent=" + original.containsKey("envdVersion")
                    + " clientIdPresent=" + original.containsKey("clientID")
                    + " clientIdNonEmpty=" + (sandbox.getClientId() != null && !sandbox.getClientId().isEmpty()));

            Sandbox connected = Sandbox.connect(sandbox.getSandboxId(), connection);
            assertEquals(2, requests.size(), "Connect must issue only one additional POST");
            assertEquals("POST /sandboxes/" + sandbox.getSandboxId() + "/connect", requests.get(1));
            assertFields(connected, snapshots.get(1));
            CodeInterpreter interpreter = CodeInterpreter.from(connected);
            assertSame(connected, interpreter.getSandbox());
            assertFields(interpreter.getSandbox(), snapshots.get(1));
            assertEquals(2, requests.size(), "Connect getters and wrapping must not issue requests");
            System.out.println("CONNECT_AND_WRAPPER_FIELDS_MATCH requests=2");

            // This explicit validation GET is outside the create/connect no-lookup assertions.
            SandboxInfo latest = sandbox.getInfo().getSandbox();
            assertEquals(3, requests.size());
            assertEquals("GET /sandboxes/" + sandbox.getSandboxId(), requests.get(2));
            assertEquals(latest.getTemplateId(), connected.getTemplateId());
            assertEquals(latest.getClientId(), connected.getClientId());
            assertEquals(latest.getEnvdVersion(), connected.getEnvdVersion());
            assertFields(sandbox, original);
            assertEquals(3, requests.size());
            System.out.println("EXPLICIT_INFO_MATCH requests=3 implicitLookups=0");
        } finally {
            try {
                // Wire capture also allows cleanup if object construction fails after successful create.
                String id = sandbox != null ? sandbox.getSandboxId() : createdId[0];
                if (id != null && !id.isEmpty()) {
                    assertTrue(Sandbox.kill(id, connection).isKilled(), "Sandbox cleanup failed");
                    System.out.println("CLEANUP_OK sandboxId=" + id);
                }
            } finally {
                http.connectionPool().evictAll();
                http.dispatcher().executorService().shutdown();
            }
        }
    }

    private static void assertFields(Sandbox sandbox, Map<String, String> wire) {
        // Missing fields are legal; presence is diagnostic, not a stronger SDK requirement.
        assertEquals(wire.get("templateID"), sandbox.getTemplateId());
        assertEquals(wire.get("clientID"), sandbox.getClientId());
        assertEquals(wire.get("envdVersion"), sandbox.getEnvdVersion());
    }
}
