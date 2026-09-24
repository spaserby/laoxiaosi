import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt password tool (same encoder as SecurityConfig: BCryptPasswordEncoder strength 10).
 * <p>
 * Usage:
 *   encode &lt;password&gt;          -&gt; print a fresh hash and self-verify
 *   verify &lt;password&gt; &lt;hash&gt;   -&gt; print MATCH / MISMATCH
 * <p>
 * Typical use: change the preset admin password without the SMS reset flow:
 *   java -cp &lt;spring-security-crypto.jar&gt; scripts/GenBcrypt.java encode MyNewPwd123
 *   psql -c "UPDATE app_user SET password_hash='&lt;hash&gt;' WHERE username='admin'"
 * <p>
 * ASCII-only on purpose (JDK17 single-file mode uses the platform charset on Windows).
 */
public class GenBcrypt {

    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        if (args.length >= 1 && "encode".equals(args[0]) && args.length >= 2) {
            String raw = args[1];
            String hash = encoder.encode(raw);
            System.out.println("hash: " + hash);
            System.out.println("self-verify: " + (encoder.matches(raw, hash) ? "MATCH" : "MISMATCH"));
            return;
        }
        if (args.length >= 3 && "verify".equals(args[0])) {
            System.out.println(encoder.matches(args[1], args[2]) ? "MATCH" : "MISMATCH");
            return;
        }
        System.out.println("usage: encode <password> | verify <password> <hash>");
    }
}
