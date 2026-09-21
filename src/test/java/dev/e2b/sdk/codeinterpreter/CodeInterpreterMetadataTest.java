package dev.e2b.sdk.codeinterpreter;

import dev.e2b.sdk.Sandbox;
import dev.e2b.sdk.client.ConnectionConfig;
import dev.e2b.sdk.model.NewSandbox;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CodeInterpreterMetadataTest {
    @ParameterizedTest
    @ValueSource(strings = {"default", "template", "options", "fromCreated", "fromConnected"})
    void metadataAccessUsesOnlyTheFactoryRequest(String factory) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String path = "fromConnected".equals(factory) ? "/sandboxes/sbx-ci/connect" : "/sandboxes";
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if ("POST".equals(request.getMethod()) && path.equals(request.getPath())) {
                        return new MockResponse().setHeader("Content-Type", "application/json")
                                .setBody("{\"sandboxID\":\"sbx-ci\",\"templateID\":\"response-template\","
                                        + "\"clientID\":\"response-client\",\"envdVersion\":\"0.5.2\"}");
                    }
                    return new MockResponse().setResponseCode(404);
                }
            });
            server.start();
            ConnectionConfig config = ConnectionConfig.builder()
                    .apiKey("e2b_test_key")
                    .apiUrl(server.url("/").toString().replaceAll("/$", ""))
                    .domain("localhost")
                    .build();
            CodeInterpreter ci;
            switch (factory) {
                case "default": ci = CodeInterpreter.create(config); break;
                case "template": ci = CodeInterpreter.create("request-template", config); break;
                case "options":
                    ci = CodeInterpreter.create("request-template", config, NewSandbox.builder().build());
                    break;
                default:
                    Sandbox sandbox = "fromConnected".equals(factory)
                            ? Sandbox.connect("sbx-ci", config) : Sandbox.create("request-template", config);
                    assertEquals(1, server.getRequestCount());
                    ci = CodeInterpreter.from(sandbox);
                    assertSame(sandbox, ci.getSandbox());
            }
            assertEquals(1, server.getRequestCount());
            for (int i = 0; i < 2; i++) {
                assertEquals("response-template", ci.getSandbox().getTemplateId());
                assertEquals("response-client", ci.getSandbox().getClientId());
                assertEquals("0.5.2", ci.getSandbox().getEnvdVersion());
            }
            assertEquals(1, server.getRequestCount());
            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("POST", request.getMethod());
            assertEquals(path, request.getPath());
            // Do not close ci: close() sends DELETE. These mock-only handles never run commands.
        }
    }
}
