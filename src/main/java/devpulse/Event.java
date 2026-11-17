package devpulse;

import java.time.Instant;
import java.util.List;

public record Event(String delivery, String repo, String branch, String author,
                    List<String> files, Instant receivedAt) {}
