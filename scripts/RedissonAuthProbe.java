import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * Redisson auth probe: does an EMPTY username break AUTH against Redis 5.0?
 *   null  -> legacy password-only AUTH (expected OK on 5.0)
 *   ""    -> if Redisson treats it as ACL username, AUTH "" <pwd> fails on 5.0
 * ASCII-only on purpose (JDK17 single-file mode uses platform charset on Windows).
 */
public class RedissonAuthProbe {

    public static void main(String[] args) {
        probe(null);
        probe("");
    }

    private static void probe(String username) {
        Config c = new Config();
        c.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setPassword("123456")
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(2);
        if (username != null) {
            c.useSingleServer().setUsername(username);
        }
        RedissonClient r = null;
        try {
            r = Redisson.create(c);
            boolean exists = r.getBucket("probe:auth:check").isExists();
            System.out.println("username=" + describe(username) + " => CONNECT OK, bucketExists=" + exists);
        } catch (Exception e) {
            System.out.println("username=" + describe(username) + " => FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (r != null) {
                r.shutdown();
            }
        }
    }

    private static String describe(String u) {
        return u == null ? "null" : "empty-string(len=0)";
    }
}
