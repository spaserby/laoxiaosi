import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/**
 * PostgreSQL query printer (companion of PgExec.java; ASCII-safe by design).
 * Usage: java -cp postgresql.jar scripts/PgQuery.java "select ..." | @file.sql
 * Prints rows as tab-separated lines; reads the query from an argument or, when the
 * argument starts with '@', from a UTF-8 file (for queries containing non-ASCII).
 */
public class PgQuery {
    public static void main(String[] args) throws Exception {
        String sql = args.length > 0 ? args[0] : "select 1";
        if (sql.startsWith("@")) {
            sql = Files.readString(Path.of(sql.substring(1)), StandardCharsets.UTF_8);
        }
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/lab_legal_kb",
                "postgres", System.getenv().getOrDefault("PG_PASSWORD", ""));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            StringBuilder head = new StringBuilder();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                if (i > 1) head.append(" | ");
                head.append(md.getColumnLabel(i));
            }
            System.out.println(head);
            int n = 0;
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    if (i > 1) row.append(" | ");
                    Object v = rs.getObject(i);
                    row.append(v == null ? "NULL" : v.toString());
                }
                System.out.println(row);
                n++;
            }
            System.out.println("(" + n + " rows)");
        }
    }
}
