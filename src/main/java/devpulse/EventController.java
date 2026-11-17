package devpulse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class EventController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final EventFeed feed;

    EventController(JdbcClient jdbc, EventFeed feed) {
        this.jdbc = jdbc;
        this.feed = feed;
    }

    @GetMapping("/events")
    List<Event> recent(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.sql("""
                SELECT delivery_id, repo, branch, author, CAST(files AS text) AS files, received_at
                FROM events ORDER BY received_at DESC LIMIT :limit""")
                .param("limit", Math.max(1, Math.min(limit, 200)))
                .query((rs, i) -> new Event(
                        rs.getString("delivery_id"), rs.getString("repo"), rs.getString("branch"),
                        rs.getString("author"), parseFiles(rs.getString("files")),
                        rs.getTimestamp("received_at").toInstant()))
                .list();
    }

    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        return feed.subscribe();
    }

    private static List<String> parseFiles(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
