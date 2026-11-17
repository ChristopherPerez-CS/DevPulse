package devpulse;

import java.util.List;

/** A push on {@code branch} by {@code author} touches files that {@code otherAuthor} changed on {@code otherBranch}. */
public record Overlap(String repo, String branch, String author,
                      String otherBranch, String otherAuthor, List<String> files) {}
