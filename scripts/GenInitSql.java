import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * db/init.sql generator (open-source packaging).
 * <p>
 * ASCII-only on purpose: JDK17 single-file source mode decodes .java with the
 * platform default charset (GBK on Windows), which corrupts non-ASCII string
 * literals. All Chinese text therefore lives in UTF-8 resource files
 * (scripts/init-template.sql + scripts/pg-schema.sql + DB rows), never in this source.
 * <p>
 * Output = template + inlined schema + idempotent UPSERT data rows.
 * Run: java -cp postgresql-42.7.4.jar scripts/GenInitSql.java   (cwd = repo root)
 */
public class GenInitSql {

    public static void main(String[] args) throws Exception {
        String template = Files.readString(Path.of("scripts/init-template.sql"), StandardCharsets.UTF_8);
        String schema = Files.readString(Path.of("scripts/pg-schema.sql"), StandardCharsets.UTF_8);

        int rows = 0;
        StringBuilder data = new StringBuilder();
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/lab_legal_kb", "postgres", System.getenv().getOrDefault("PG_PASSWORD", ""));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     select id, law_name, article_no, category, title, version_info,
                            chapter_info, section_info, content, deleted
                     from law_article order by id""")) {
            while (rs.next()) {
                data.append("INSERT INTO law_article (id, law_name, article_no, category, title, version_info, chapter_info, section_info, content, deleted) VALUES (")
                        .append(rs.getLong("id")).append(", ")
                        .append(q(rs.getString("law_name"))).append(", ")
                        .append(q(rs.getString("article_no"))).append(", ")
                        .append(q(rs.getString("category"))).append(", ")
                        .append(q(rs.getString("title"))).append(", ")
                        .append(q(rs.getString("version_info"))).append(", ")
                        .append(q(rs.getString("chapter_info"))).append(", ")
                        .append(q(rs.getString("section_info"))).append(", ")
                        .append(q(rs.getString("content"))).append(", ")
                        .append(rs.getInt("deleted"))
                        .append(") ON CONFLICT (id) DO UPDATE SET ")
                        .append("law_name = EXCLUDED.law_name, article_no = EXCLUDED.article_no, ")
                        .append("category = EXCLUDED.category, title = EXCLUDED.title, ")
                        .append("version_info = EXCLUDED.version_info, chapter_info = EXCLUDED.chapter_info, ")
                        .append("section_info = EXCLUDED.section_info, content = EXCLUDED.content, ")
                        .append("deleted = EXCLUDED.deleted;\n");
                rows++;
            }
        }

        String out = template
                .replace("@@ROWS@@", String.valueOf(rows))
                .replace("@@SCHEMA@@", schema.strip())
                .replace("@@DATA@@", data.toString().stripTrailing());

        Path target = Path.of("db/init.sql");
        Files.createDirectories(target.getParent());
        Files.writeString(target, out, StandardCharsets.UTF_8);
        System.out.println("written: " + target.toAbsolutePath());
        System.out.println("law_article rows: " + rows);
        System.out.println("file size: " + Files.size(target) / 1024 + " KB");
    }

    /** SQL literal escaping: double the single quotes; NULL stays NULL. */
    private static String q(String s) {
        return s == null ? "NULL" : "'" + s.replace("'", "''") + "'";
    }
}
