package devpulse;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookController {

    public record Event(String delivery, String repo, String branch, String author,
                        List<String> files, Instant receivedAt) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HMAC = "HmacSHA256";
    private static final String BRANCH_PREFIX = "refs/heads/";

    private final byte[] secret;
    private final Map<String, Event> events = new ConcurrentHashMap<>();

    WebhookController(@Value("${devpulse.webhook-secret}") String secret) {
        if (secret.isBlank()) throw new IllegalStateException("devpulse.webhook-secret must not be blank");
        this.secret = secret.getBytes(UTF_8);
    }

    Map<String, Event> events() {
        return events;
    }

    @PostMapping("/webhook")
    ResponseEntity<Void> receive(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String type,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery) {

        // 1. Authenticate first: nothing is parsed or stored before this passes.
        if (signature == null || !MessageDigest.isEqual(sign(body).getBytes(UTF_8), signature.getBytes(UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        if (delivery == null || delivery.isBlank() || type == null) {
            return ResponseEntity.badRequest().build();
        }

        // 2. Only push events matter for now; ping and everything else get a 200 and are ignored.
        if (!"push".equals(type)) return ResponseEntity.ok().build();

        JsonNode p;
        try {
            p = JSON.readTree(body);
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }

        String ref = p.path("ref").asText("");
        if (!ref.startsWith(BRANCH_PREFIX)) return ResponseEntity.ok().build(); // tag pushes etc.

        String repo = p.path("repository").path("full_name").asText("");
        String author = p.path("sender").path("login").asText("");
        if (repo.isBlank() || author.isBlank()) return ResponseEntity.badRequest().build();

        Set<String> files = new LinkedHashSet<>();
        for (JsonNode commit : p.path("commits"))
            for (String key : List.of("added", "modified", "removed"))
                for (JsonNode f : commit.path(key)) files.add(f.asText());

        Event event = new Event(delivery, repo, ref.substring(BRANCH_PREFIX.length()), author,
                List.copyOf(files), Instant.now());

        // 3. Atomic dedup: a retried delivery returns 200 without being stored twice.
        return events.putIfAbsent(delivery, event) == null
                ? ResponseEntity.status(201).build()
                : ResponseEntity.ok().build();
    }

    private String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
