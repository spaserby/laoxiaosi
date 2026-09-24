import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * db/init.sql 整文件执行器：sandbox/CI 环境 psql 不可用时的验证通道。
 * 用法：java -cp postgresql-42.7.4.jar scripts/RunInitSql.java [sql文件，默认 db/init.sql]
 * 说明：PG JDBC 支持单次 execute 执行多语句（简单查询协议），整份脚本一次提交；
 * 幂等脚本可安全重复跑，跑完打印 law_article 计数供验收。
 */
public class RunInitSql {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "db/init.sql");
        String sql = Files.readString(file, StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/lab_legal_kb", "postgres", System.getenv().getOrDefault("PG_PASSWORD", ""));
             Statement st = c.createStatement()) {
            st.execute(sql);
            System.out.println("EXEC OK: " + file);
            try (ResultSet rs = st.executeQuery("select count(*) from law_article where deleted = 0")) {
                rs.next();
                System.out.println("law_article (deleted=0) = " + rs.getInt(1));
            }
        }
    }
}
