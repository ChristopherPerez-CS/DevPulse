package devpulse;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HMAC = "HmacSHA256";
    private static final String BRANCH_PREFIX = "refs/heads/";

    private final byte[] secret;
    private final JdbcClient jdbc;

    WebhookController(@Value("${devpulse.webhook-secret}") String secret, JdbcClient jdbc) {
        if (secret.isBlank()) throw new IllegalStateException("devpulse.webhook-secret must not be blank");
        this.secret = secret.getBytes(UTF_8);
        this.jdbc = jdbc;
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

        // 3. Atomic dedup: the primary key on delivery_id decides, not application code.
        //    A database error propagates as a 500, so GitHub retries instead of losing the event.
        int inserted = jdbc.sql("""
                INSERT INTO events (delivery_id, repo, branch, author, files)
                VALUES (:id, :repo, :branch, :author, CAST(:files AS jsonb))
                ON CONFLICT (delivery_id) DO NOTHING""")
                .param("id", delivery)
                .param("repo", repo)
                .param("branch", ref.substring(BRANCH_PREFIX.length()))
                .param("author", author)
                .param("files", JSON.valueToTree(files).toString())
                .update();

        return inserted == 1 ? ResponseEntity.status(201).build() : ResponseEntity.ok().build();
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
