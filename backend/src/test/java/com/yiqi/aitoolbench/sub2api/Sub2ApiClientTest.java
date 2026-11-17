package com.yiqi.aitoolbench.sub2api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Sub2ApiClientTest {
    private HttpServer server;
    private Sub2ApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/admin/workbench/capabilities", e -> respond(e, 200, "{\"data\":{\"contractVersion\":1}}"));
        server.start();
        var properties = new Sub2ApiProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "admin-secret", 2, 2);
        client = new Sub2ApiClient(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void accountListUsesAdminKeyAndNeverReturnsCredentialsOrProxySecrets() {
        var receivedKey = new AtomicReference<String>();
        server.createContext("/api/v1/admin/accounts", exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            respond(exchange, 200, """
                    {"code":0,"message":"success","data":{"items":[{
                      "id":9,"name":"OpenAI-01","platform":"openai","type":"oauth","status":"active",
                      "credentials":{"access_token":"never-return"},
                      "extra":{"codex_5h_used_percent":25,"codex_7d_used_percent":40,"private":"hidden"},
                      "proxy":{"id":3,"name":"Singapore","protocol":"socks5h","status":"active",
                        "host":"secret-host","username":"secret-user","password":"secret-password"}
                    }],"total":1,"page":1,"page_size":10,"pages":1}}
                    """);
        });

        var result = client.listAccounts(1, 10, "openai", "", "");

        assertThat(receivedKey.get()).isEqualTo("admin-secret");
        assertThat(result).containsEntry("total", 1L).containsEntry("totalPages", 1);
        var account = ((java.util.List<Map<String, Object>>) result.get("items")).getFirst();
        assertThat(account).containsEntry("name", "OpenAI-01").doesNotContainKeys("credentials", "extra");
        assertThat((Map<String, Object>) account.get("usage"))
                .containsEntry("codex5hUsedPercent", 25)
                .containsEntry("codex7dUsedPercent", 40)
                .doesNotContainKey("private");
        assertThat((Map<String, Object>) account.get("proxy"))
                .containsEntry("name", "Singapore")
                .doesNotContainKeys("host", "username", "password");
    }

    @Test
    void windowAveragesUseAllHealthyAccountsWithValidReadingsAcrossPages() {
        server.createContext("/api/v1/admin/accounts", exchange -> {
            var query = exchange.getRequestURI().getQuery();
            if (query.contains("platform=gemini")) {
                respond(exchange, 200, "{\"data\":{\"items\":[{\"id\":7,\"platform\":\"gemini\",\"type\":\"oauth\",\"status\":\"active\"}],\"pages\":1}}");
            } else if (query.contains("page=2")) {
                respond(exchange, 200, """
                    {"data":{"items":[
                      {"id":3,"platform":"openai","type":"oauth","status":"active","extra":{"codex_5h_used_percent":80}},
                      {"id":4,"platform":"openai","type":"oauth","status":"active","schedulable":false,"extra":{"codex_5h_used_percent":0,"codex_7d_used_percent":0}},
                      {"id":5,"platform":"openai","type":"oauth","status":"inactive","extra":{"codex_5h_used_percent":0,"codex_7d_used_percent":0}},
                      {"id":6,"platform":"openai","type":"oauth","status":"active","extra":{"codex_5h_used_percent":110,"codex_7d_used_percent":10}}
                    ],"pages":2}}
                    """);
            } else {
                respond(exchange, 200, """
                    {"data":{"items":[
                      {"id":1,"platform":"openai","type":"oauth","status":"active","extra":{"codex_5h_used_percent":20,"codex_7d_used_percent":40}},
                      {"id":2,"platform":"openai","type":"oauth","status":"active","extra":{"codex_5h_used_percent":60,"codex_7d_used_percent":80}}
                    ],"pages":2}}
                    """);
            }
        });

        var result = client.listAccounts(1, 1, null, null, null);

        assertThat((java.util.List<?>)result.get("items")).hasSize(1);
        var summary = (Map<String,Object>)result.get("summary");
        assertThat(summary.get("healthy")).isEqualTo(5);
        var fiveHour = (Map<String,Object>)summary.get("window5h");
        var sevenDay = (Map<String,Object>)summary.get("window7d");
        assertThat(fiveHour.get("accountCount")).isEqualTo(3);
        assertThat((double)fiveHour.get("averageRemainingPercent")).isCloseTo(46.6667, org.assertj.core.data.Offset.offset(0.001));
        assertThat(sevenDay.get("accountCount")).isEqualTo(3);
        assertThat((double)sevenDay.get("averageRemainingPercent")).isCloseTo(56.6667, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void openAiCompletionCreatesAccountServerSideAndSanitizesResponse() {
        var requestBody = new AtomicReference<String>();
        server.createContext("/api/v1/admin/openai/exchange-code", exchange -> {
            requestBody.set(readBody(exchange));
            respond(exchange, 200, "{\"data\":{\"access_token\":\"private\",\"expires_at\":1800000000}}");
        });
        server.createContext("/api/v1/admin/accounts", exchange -> {
            respond(exchange, 200, """
                    {"code":0,"message":"success","data":{"id":11,"name":"OpenAI-02","platform":"openai",
                    "type":"oauth","status":"active","credentials":{"refresh_token":"never-return"}}}
                    """);
        });

        var account = client.completeOAuth(new Sub2ApiClient.OAuthComplete(
                "openai", "session-1", "fallback-state",
                "http://localhost/callback?code=oauth-code&state=callback-state",
                null, 3L, "OpenAI-02", 2, 0, null, null));

        assertThat(requestBody.get()).contains("\"session_id\":\"session-1\"")
                .contains("\"code\":\"oauth-code\"")
                .contains("\"state\":\"callback-state\"")
                .contains("\"proxy_id\":3");
        assertThat(account).containsEntry("id", 11).containsEntry("name", "OpenAI-02")
                .doesNotContainKeys("credentials", "accessToken", "refreshToken");
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void accountTestRejectsNativeDataErrorEvenWhenHttpSucceeded() {
        server.createContext("/api/v1/admin/accounts/1/test", e -> respond(e, 200,
                "data: {\"type\": \"error\", \"error\": \"upstream unavailable\"}\n\n"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.test(1))
                .isInstanceOf(Sub2ApiException.class).hasMessageContaining("失败");
    }

    @Test
    void accountTestRequiresSuccessfulCompletionEvent() {
        server.createContext("/api/v1/admin/accounts/1/test", e -> respond(e, 200,
                "data: {\"type\":\"test_start\"}\n\n"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.test(1))
                .isInstanceOf(Sub2ApiException.class).hasMessageContaining("未完成");
    }

    @Test
    void accountTestAcceptsNativeCompletionAfterProgress() {
        server.createContext("/api/v1/admin/accounts/1/test", e -> respond(e, 200,
                "data: {\"type\":\"test_start\"}\r\n\r\ndata: {\"type\":\"test_complete\",\"success\":true}\r\n\r\n"));
        assertThat(client.test(1)).containsEntry("success", true);
    }

    @Test
    void accountTestPassesConfiguredModelInsteadOfNativeObsoleteDefault() {
        var body = new AtomicReference<String>();
        server.createContext("/api/v1/admin/accounts/1/test", e -> {
            body.set(readBody(e));
            respond(e, 200, "data: {\"type\":\"test_complete\",\"success\":true}\n\n");
        });
        assertThat(client.test(1, "gpt-test-configured")).containsEntry("success", true);
        assertThat(body.get()).contains("\"model_id\":\"gpt-test-configured\"");
    }

    @Test
    void newOpenAiAccountIsCreatedInValidatedWorkbenchGroup() {
        var body = new AtomicReference<String>();
        server.createContext("/api/v1/admin/groups/2", e -> respond(e, 200,
                "{\"data\":{\"id\":2,\"platform\":\"composite\",\"status\":\"active\"}}"));
        server.createContext("/api/v1/admin/openai/exchange-code", e -> respond(e, 200,
                "{\"data\":{\"access_token\":\"private\",\"expires_at\":1800000000}}"));
        server.createContext("/api/v1/admin/accounts", e -> {
            body.set(readBody(e));
            respond(e, 200, "{\"data\":{\"id\":12}}");
        });
        client.completeOAuth(new Sub2ApiClient.OAuthComplete("openai", "session", "state", "code",
                null, 3L, "QA", 1, 1, null, null), 2);
        assertThat(body.get()).contains("\"group_ids\":[2]", "\"workbench_threshold_5h\":90", "\"workbench_threshold_7d\":95", "\"expires_at\":\"2027-01-15T08:00:00Z\"");
    }

    @Test
    void newGoogleAccountUsesSameWorkbenchGroupWithoutLeakingTokens() {
        var body = new AtomicReference<String>();
        server.createContext("/api/v1/admin/groups/2", e -> respond(e, 200,
                "{\"data\":{\"platform\":\"composite\",\"status\":\"active\"}}"));
        server.createContext("/api/v1/admin/gemini/oauth/exchange-code", e -> respond(e, 200,
                "{\"data\":{\"access_token\":\"private-token\"}}"));
        server.createContext("/api/v1/admin/accounts", e -> {
            body.set(readBody(e));
            respond(e, 200, "{\"data\":{\"id\":13,\"credentials\":{\"access_token\":\"private-token\"}}}");
        });
        var result = client.completeOAuth(new Sub2ApiClient.OAuthComplete("gemini", "session", "state", "code",
                null, 3L, "QA", 1, 1, null, null), 2);
        assertThat(body.get()).contains("\"group_ids\":[2]");
        assertThat(result).doesNotContainKey("credentials");
    }

    @Test
    void invalidWorkbenchGroupDoesNotConsumeAuthorizationCode() {
        var exchanged = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/api/v1/admin/groups/2", e -> respond(e, 200,
                "{\"data\":{\"platform\":\"composite\",\"status\":\"inactive\"}}"));
        server.createContext("/api/v1/admin/openai/exchange-code", e -> {
            exchanged.set(true); respond(e, 200, "{\"data\":{\"id\":12}}");
        });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.completeOAuth(
                new Sub2ApiClient.OAuthComplete("openai", "session", "state", "code", null, 3L,
                        "QA", 1, 1, null, null), 2)).isInstanceOf(IllegalArgumentException.class);
        assertThat(exchanged).isFalse();
    }

    @Test
    void officialApiAccountsUseCorrectPlatformBaseAndGroupWithoutReturningKey() {
        var body = new AtomicReference<String>();
        server.createContext("/api/v1/admin/groups/2", e -> respond(e, 200, "{\"data\":{\"platform\":\"composite\",\"status\":\"active\"}}"));
        server.createContext("/api/v1/admin/accounts", e -> {body.set(readBody(e));respond(e,200,"{\"data\":{\"id\":20,\"type\":\"apikey\",\"credentials\":{\"api_key\":\"private\"}}}");});
        for (String platform : java.util.List.of("openai","gemini")) {
            var result=client.createApiKeyAccount(new Sub2ApiClient.ApiKeyAccount(platform,"QA",null,5,1,"private","note"),2);
            assertThat(body.get()).contains("\"group_ids\":[2]", "\"type\":\"apikey\"", "\"platform\":\""+platform+"\"");
            assertThat(body.get()).contains(platform.equals("openai")?"https://api.openai.com":"https://generativelanguage.googleapis.com");
            assertThat(body.get()).doesNotContain("workbench_threshold", "refresh_token");
            assertThat(result).doesNotContainKey("credentials");
        }
    }

    @Test
    void apiKeyReplacementPreservesOtherCredentialsAndEmptyEditKeepsKey() {
        var body = new AtomicReference<String>();
        server.createContext("/api/v1/admin/accounts/20", e -> {
            if(e.getRequestMethod().equals("GET")) respond(e,200,"{\"data\":{\"type\":\"apikey\",\"platform\":\"gemini\",\"credentials\":{\"api_key\":\"old\",\"base_url\":\"https://generativelanguage.googleapis.com\",\"tier_id\":\"aistudio_paid\"}}}");
            else {body.set(readBody(e));respond(e,200,"{\"data\":{\"id\":20}}");}
        });
        client.editAccount(20,new Sub2ApiClient.AccountEdit("QA","",null,2,null,null,1,"new-private"));
        assertThat(body.get()).contains("new-private", "aistudio_paid", "generativelanguage.googleapis.com");
        client.editAccount(20,new Sub2ApiClient.AccountEdit("QA","",null,2,null,null,1,""));
        assertThat(body.get()).doesNotContain("credentials", "api_key");
    }

    @Test
    void invalidApiKeyAndWindowAreRejectedBeforeNativeMutation() {
        org.assertj.core.api.Assertions.assertThatThrownBy(()->client.createApiKeyAccount(new Sub2ApiClient.ApiKeyAccount("gemini","QA",null,1,1,"bad key",null),2)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->client.completeOAuth(new Sub2ApiClient.OAuthComplete("openai","session","state","code",null,null,"QA",1,1,null,null,101,95,null),0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void apiCreateErrorsCannotEchoSubmittedCredential() {
        server.createContext("/api/v1/admin/groups/2", e -> respond(e,200,"{\"data\":{\"platform\":\"composite\",\"status\":\"active\"}}"));
        server.createContext("/api/v1/admin/accounts", e -> respond(e,400,"{\"message\":\"private-api-key was rejected\"}"));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->client.createApiKeyAccount(new Sub2ApiClient.ApiKeyAccount("openai","QA",null,1,1,"private-api-key",null),2))
                .isInstanceOf(Sub2ApiException.class).hasMessageNotContaining("private-api-key");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
