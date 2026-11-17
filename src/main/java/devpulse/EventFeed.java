package devpulse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
class EventFeed {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L; // browsers reconnect on their own

    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();

    SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        clients.add(emitter);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> clients.remove(emitter));
        emitter.onError(e -> clients.remove(emitter));
        return emitter;
    }

    void publish(Event event) {
        broadcast("push", event.delivery(), event);
    }

    void warn(Overlap overlap) {
        broadcast("overlap", null, overlap);
    }

    private void broadcast(String name, String id, Object data) {
        for (SseEmitter client : clients) {
            try {
                SseEmitter.SseEventBuilder builder = SseEmitter.event().name(name);
                if (id != null) builder.id(id);
                client.send(builder.data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                clients.remove(client); // disconnected client
            }
        }
    }
}
