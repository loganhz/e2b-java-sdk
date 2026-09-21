package dev.e2b.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.e2b.sdk.client.ConnectionConfig;
import dev.e2b.sdk.model.NewSandbox;
import dev.e2b.sdk.model.SandboxInfo;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SandboxResponseFieldsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private ConnectionConfig config;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        config = ConnectionConfig.builder()
                .apiKey("e2b_test_key")
                .apiUrl(server.url("/").toString().replaceAll("/$", ""))
                .domain("localhost")
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        // Sandbox.close() would send DELETE and is not needed for these mock-only handles.
        server.shutdown();
    }

    @ParameterizedTest
    @ValueSource(strings = {"createDefault", "createNamed", "createOptions", "createFull",
            "connect", "connectTimeout"})
    void factoriesRetainResponseFieldsWithoutLookup(String factory) throws Exception {
        Map<String, String> body = responseFields();
        servePost(factoryPath(factory), body);

        Sandbox sandbox = create(factory);

        assertEquals(1, server.getRequestCount());
        assertFields(sandbox, body);
        assertFields(sandbox, body);
        assertEquals(1, server.getRequestCount());
        RecordedRequest request = assertRequest("POST", factoryPath(factory));
        if ("connectTimeout".equals(factory)) {
            assertEquals(120, mapper.readTree(request.getBody().readUtf8()).path("timeout").asInt());
        } else if (factory.startsWith("create") && !"createDefault".equals(factory)) {
            assertEquals("request-alias:v1",
                    mapper.readTree(request.getBody().readUtf8()).path("templateID").asText());
        }
    }

    static Stream<Arguments> missingFields() {
        return Stream.of("createNamed", "connect").flatMap(factory ->
                Stream.of("templateID", "clientID", "envdVersion").flatMap(field ->
                        Stream.of("missing", "null", "empty").map(value ->
                                Arguments.of(factory, field, value))));
    }

    @ParameterizedTest(name = "{0}: {1}={2}")
    @MethodSource("missingFields")
    void absentValuesNeverTriggerLookup(String factory, String field, String value) throws Exception {
        Map<String, String> body = responseFields();
        if ("missing".equals(value)) {
            body.remove(field);
        } else {
            body.put(field, "null".equals(value) ? null : "");
        }
        servePost(factoryPath(factory), body);

        Sandbox sandbox = create(factory);

        assertEquals(1, server.getRequestCount());
        assertFields(sandbox, body);
        assertFields(sandbox, body);
        assertEquals(1, server.getRequestCount(), "Missing fields must not cause an implicit GET");
        assertRequest("POST", factoryPath(factory));
    }

    @Test
    void explicitInfoAndReconnectDoNotChangeOriginalSnapshot() throws Exception {
        Map<String, String> initial = responseFields();
        Map<String, String> latest = responseFields();
        latest.put("templateID", "latest-template");
        latest.put("clientID", "latest-client");
        latest.put("envdVersion", "0.6.0");
        String initialJson = mapper.writeValueAsString(initial);
        String latestJson = mapper.writeValueAsString(latest);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("POST".equals(request.getMethod()) && "/sandboxes".equals(request.getPath())) {
                    return json(initialJson);
                }
                if (("GET".equals(request.getMethod()) && "/sandboxes/sbx-fields".equals(request.getPath()))
                        || ("POST".equals(request.getMethod())
                        && "/sandboxes/sbx-fields/connect".equals(request.getPath()))) {
                    return json(latestJson);
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        Sandbox original = Sandbox.create("request-alias:v1", config);
        assertFields(original, initial);
        assertEquals(1, server.getRequestCount());
        assertRequest("POST", "/sandboxes");

        SandboxInfo info = original.getInfo().getSandbox();
        assertEquals(latest.get("templateID"), info.getTemplateId());
        assertEquals(latest.get("clientID"), info.getClientId());
        assertEquals(latest.get("envdVersion"), info.getEnvdVersion());
        assertFields(original, initial);
        assertEquals(2, server.getRequestCount());
        assertRequest("GET", "/sandboxes/sbx-fields");

        Sandbox connected = Sandbox.connect(original.getSandboxId(), config);
        assertFields(connected, latest);
        assertFields(original, initial);
        assertEquals(3, server.getRequestCount());
        assertRequest("POST", "/sandboxes/sbx-fields/connect");
    }

    private Sandbox create(String factory) {
        switch (factory) {
            case "createDefault": return Sandbox.create(config);
            case "createNamed": return Sandbox.create("request-alias:v1", config);
            case "createOptions":
                return Sandbox.create(NewSandbox.builder().templateId("request-alias:v1").build(), config);
            case "createFull":
                return Sandbox.create("request-alias:v1", config, NewSandbox.builder().timeout(120).build());
            case "connect": return Sandbox.connect("sbx-fields", config);
            case "connectTimeout": return Sandbox.connect("sbx-fields", config, 120);
            default: throw new IllegalArgumentException(factory);
        }
    }

    private static String factoryPath(String factory) {
        return factory.startsWith("connect") ? "/sandboxes/sbx-fields/connect" : "/sandboxes";
    }

    private Map<String, String> responseFields() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("sandboxID", "sbx-fields");
        body.put("templateID", "response-template");
        body.put("clientID", "response-client");
        // Preserve the wire value, including whitespace; do not parse or normalize it.
        body.put("envdVersion", " 0.5.2.fc-custom ");
        return body;
    }

    private void servePost(String path, Map<String, String> body) throws IOException {
        String encoded = mapper.writeValueAsString(body);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("POST".equals(request.getMethod()) && path.equals(request.getPath())) {
                    return json(encoded);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private RecordedRequest assertRequest(String method, String path) throws InterruptedException {
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(method, request.getMethod());
        assertEquals(path, request.getPath());
        return request;
    }

    private static void assertFields(Sandbox sandbox, Map<String, String> body) {
        assertEquals(body.get("templateID"), sandbox.getTemplateId());
        assertEquals(body.get("clientID"), sandbox.getClientId());
        assertEquals(body.get("envdVersion"), sandbox.getEnvdVersion());
    }
}
