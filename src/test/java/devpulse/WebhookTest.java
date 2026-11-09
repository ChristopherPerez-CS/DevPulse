package devpulse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "devpulse.webhook-secret=test-secret")
@AutoConfigureMockMvc
@Testcontainers
class WebhookTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static final String PUSH = """
        {"ref":"refs/heads/feature-x","repository":{"full_name":"acme/app"},"sender":{"login":"alice"},
         "commits":[{"added":["a.txt"],"modified":["b.txt"],"removed":[]},
                    {"added":[],"modified":["b.txt"],"removed":["c.txt"]}]}""";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM events").update();
    }

    @Test
    void signedPushIsStoredOnce() throws Exception {
        send("push", "d1", sign(PUSH), PUSH).andExpect(status().isCreated());

        assertEquals(1L, count());
        assertEquals("acme/app feature-x alice",
                jdbc.sql("SELECT repo || ' ' || branch || ' ' || author FROM events WHERE delivery_id = 'd1'")
                        .query(String.class).single());
        assertEquals(List.of("a.txt", "b.txt", "c.txt"),
                jdbc.sql("SELECT jsonb_array_elements_text(files) FROM events WHERE delivery_id = 'd1'")
                        .query(String.class).list());
    }

    @Test
    void duplicateDeliveryIsStoredOnce() throws Exception {
        send("push", "d1", sign(PUSH), PUSH).andExpect(status().isCreated());
        send("push", "d1", sign(PUSH), PUSH).andExpect(status().isOk());

        assertEquals(1L, count());
    }

    @Test
    void tamperedBodyIsRejected() throws Exception {
        send("push", "d1", sign(PUSH), PUSH.replace("alice", "mallory")).andExpect(status().isUnauthorized());

        assertEquals(0L, count());
    }

    @Test
    void missingSignatureIsRejected() throws Exception {
        send("push", "d1", null, PUSH).andExpect(status().isUnauthorized());

        assertEquals(0L, count());
    }

    @Test
    void pingIsAcceptedButNotStored() throws Exception {
        String ping = "{\"zen\":\"ok\"}";
        send("ping", "d2", sign(ping), ping).andExpect(status().isOk());

        assertEquals(0L, count());
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM events").query(Long.class).single();
    }

    private ResultActions send(String event, String delivery, String signature, String body) throws Exception {
        MockHttpServletRequestBuilder req = post("/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-GitHub-Event", event)
                .header("X-GitHub-Delivery", delivery);
        if (signature != null) req.header("X-Hub-Signature-256", signature);
        return mvc.perform(req);
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(UTF_8)));
    }
}
