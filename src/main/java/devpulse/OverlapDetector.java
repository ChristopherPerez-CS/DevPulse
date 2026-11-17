package devpulse;

import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class OverlapDetector {

    // Same repo, different branch AND different author, shared file, within the last 7 days.
    private static final String SQL = """
            SELECT e.branch, e.author, array_agg(DISTINCT t.file ORDER BY t.file) AS shared
            FROM events e, jsonb_array_elements_text(e.files) AS t(file)
            WHERE e.repo = :repo
              AND e.branch <> :branch
              AND e.author <> :author
              AND e.received_at > now() - interval '7 days'
              AND t.file IN (:files)
            GROUP BY e.branch, e.author""";

    private final JdbcClient jdbc;

    OverlapDetector(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Overlap> find(String repo, String branch, String author, Collection<String> files) {
        if (files.isEmpty()) return List.of(); // IN () is invalid SQL, and nothing can overlap anyway
        return jdbc.sql(SQL)
                .param("repo", repo)
                .param("branch", branch)
                .param("author", author)
                .param("files", files)
                .query((rs, i) -> new Overlap(repo, branch, author,
                        rs.getString("branch"), rs.getString("author"),
                        List.of((String[]) rs.getArray("shared").getArray())))
                .list();
    }
}
