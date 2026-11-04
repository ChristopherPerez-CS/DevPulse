package devpulse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = "devpulse.webhook-secret=test-secret")
@AutoConfigureMockMvc
class WebhookTest {

    static final String PUSH = """
        {"ref":"refs/heads/feature-x","repository":{"full_name":"acme/app"},"sender":{"login":"alice"},
         "commits":[{"added":["a.txt"],"modified":["b.txt"],"removed":[]},
                    {"added":[],"modified":["b.txt"],"removed":["c.txt"]}]}""";

    @Autowired MockMvc mvc;
    @Autowired WebhookController controller;

    @BeforeEach
    void reset() {
        controller.events().clear();
    }

    @Test
    void signedPushIsStoredOnce() throws Exception {
        send("push", "d1", sign(PUSH), PUSH).andExpect(status().isCreated());

        assertEquals(1, controller.events().size());
        var e = controller.events().get("d1");
        assertEquals("acme/app", e.repo());
        assertEquals("feature-x", e.branch());
        assertEquals("alice", e.author());
        assertEquals(List.of("a.txt", "b.txt", "c.txt"), e.files());
    }

    @Test
    void duplicateDeliveryIsStoredOnce() throws Exception {
        send("push", "d1", sign(PUSH), PUSH).andExpect(status().isCreated());
        send("push", "d1", sign(PUSH), PUSH).andExpect(status().isOk());

        assertEquals(1, controller.events().size());
    }

    @Test
    void tamperedBodyIsRejected() throws Exception {
        send("push", "d1", sign(PUSH), PUSH.replace("alice", "mallory")).andExpect(status().isUnauthorized());

        assertTrue(controller.events().isEmpty());
    }

    @Test
    void missingSignatureIsRejected() throws Exception {
        send("push", "d1", null, PUSH).andExpect(status().isUnauthorized());

        assertTrue(controller.events().isEmpty());
    }

    @Test
    void pingIsAcceptedButNotStored() throws Exception {
        String ping = "{\"zen\":\"ok\"}";
        send("ping", "d2", sign(ping), ping).andExpect(status().isOk());

        assertTrue(controller.events().isEmpty());
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
