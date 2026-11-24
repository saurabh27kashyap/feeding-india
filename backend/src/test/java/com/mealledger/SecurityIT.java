package com.mealledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.mealledger.auth.Role;
import com.mealledger.order.OrderGenerationService;

/** Over real HTTP: session login with CSRF, role checks, data scoping, and the FRAT API key. */
class SecurityIT extends IntegrationTest {

    @Autowired Environment env;
    @Autowired PasswordEncoder encoder;
    @Autowired OrderGenerationService generation;

    long myOrder;
    long otherOrder;

    @BeforeEach
    void setUp() {
        kitchen(1800);
        long mine = centre("Mine", 40, "LUNCH");
        long other = centre("Other", 40, "LUNCH");
        user("9000000001", Role.CENTRE, mine, null, encoder.encode("pw"));
        at(DAY.minusDays(1), "10:00:00");
        generation.generate(DAY);
        myOrder = orderId(mine, DAY, "LUNCH");
        otherOrder = orderId(other, DAY, "LUNCH");
    }

    @Test
    void centreUserIsScopedToItsOwnCentre() throws Exception {
        Session s = new Session();
        assertThat(s.get("/api/centre/orders").statusCode()).isEqualTo(401);

        assertThat(s.send("POST", "/api/auth/login", "{\"phone\":\"9000000001\",\"password\":\"wrong\"}").statusCode()).isEqualTo(401);
        assertThat(s.send("POST", "/api/auth/login", "{\"phone\":\"9000000001\",\"password\":\"pw\"}").statusCode()).isEqualTo(200);

        HttpResponse<String> home = s.get("/api/centre/orders");
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).contains("\"id\":" + myOrder).doesNotContain("Other");

        assertThat(s.send("PUT", "/api/centre/orders/" + myOrder, "{\"qty\":33}").statusCode()).isEqualTo(200);
        // Another centre's order looks exactly like one that does not exist.
        assertThat(s.send("PUT", "/api/centre/orders/" + otherOrder, "{\"qty\":33}").statusCode()).isEqualTo(404);
        // Wrong role.
        assertThat(s.get("/api/ops/dashboard").statusCode()).isEqualTo(403);
        assertThat(s.get("/api/kitchen/prep-list?date=" + DAY).statusCode()).isEqualTo(403);
        // Validation.
        assertThat(s.send("PUT", "/api/centre/orders/" + myOrder, "{\"qty\":0}").statusCode()).isEqualTo(400);
    }

    @Test
    void writesWithoutCsrfTokenAreRejected() throws Exception {
        Session s = new Session();
        s.send("POST", "/api/auth/login", "{\"phone\":\"9000000001\",\"password\":\"pw\"}");
        HttpRequest noToken = HttpRequest.newBuilder(s.uri("/api/centre/orders/" + myOrder))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"qty\":33}")).build();
        assertThat(s.http.send(noToken, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    @Test
    void fratIntakeNeedsTheApiKey() throws Exception {
        at(DAY, "10:00:00");
        HttpClient http = HttpClient.newHttpClient();
        String body = "{\"entries\":[{\"centreId\":1,\"serviceDate\":\"" + DAY + "\",\"mealSlot\":\"LUNCH\",\"present\":31},"
                + "{\"centreId\":999,\"serviceDate\":\"" + DAY + "\",\"mealSlot\":\"LUNCH\",\"present\":5}]}";
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + "/api/integrations/frat/attendance"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        assertThat(http.send(req.copy().header("X-Api-Key", "nope").build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);

        HttpResponse<String> ok = http.send(req.copy().header("X-Api-Key", "frat-demo-key").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("\"accepted\":1").contains("\"index\":1");

        // Same batch again: nothing changes (idempotent resend).
        HttpResponse<String> again = http.send(req.copy().header("X-Api-Key", "frat-demo-key").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(again.body()).contains("\"accepted\":0").contains("\"unchanged\":1");
    }

    private String base() {
        return "http://localhost:" + env.getProperty("local.server.port");
    }

    /** A browser-like client: keeps cookies and echoes the XSRF-TOKEN cookie as a header. */
    class Session {
        final CookieManager cookies = new CookieManager();
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        URI uri(String path) {
            return URI.create(base() + path);
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> send(String method, String path, String json) throws Exception {
            if (token() == null) {
                get("/api/auth/me"); // first visit: receive the CSRF cookie
            }
            HttpRequest req = HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", token())
                    .method(method, HttpRequest.BodyPublishers.ofString(json))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        }

        String token() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElse(null);
        }
    }
}
