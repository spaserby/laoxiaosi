package com.law.backend.auth;

import com.law.backend.user.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 JWT、密码重置、SQL 和过滤器，短信验证码边界使用合成响应。 */
class AuthRevocationTest {
    EmbeddedDatabase db; JdbcTemplate jdbc; UserMapper users; JwtService jwt; AuthService auth; JwtAuthFilter filter;
    long id;
    @BeforeEach void prepare() throws Exception {
        db=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build(); jdbc=new JdbcTemplate(db);
        jdbc.execute("create table app_user(id bigserial primary key,username varchar(64),password_hash varchar(100),phone varchar(20),role varchar(20),status int,avatar varchar(16),created_at timestamp,auth_version bigint not null default 0)");
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(db); var sf=factory.getObject();
        sf.getConfiguration().setMapUnderscoreToCamelCase(true); sf.getConfiguration().addMapper(UserMapper.class);
        users=new SqlSessionTemplate(sf).getMapper(UserMapper.class);
        jwt=new JwtService(new AuthProperties(),new MockEnvironment());
        var sms=mock(SmsService.class); when(sms.verify(anyString(),anyString())).thenReturn(true);
        var encoder=new BCryptPasswordEncoder(4); auth=new AuthService(users,sms,jwt,encoder); filter=new JwtAuthFilter(jwt,users);
        var user=new UserEntity(); user.setUsername("synthetic_user"); user.setRole("ADMIN"); user.setStatus(0);
        user.setPhone("1"+"9".repeat(10)); user.setPasswordHash(encoder.encode("synthetic"+"Input2026")); users.insert(user); id=user.getId();
    }
    @AfterEach void close() { db.shutdown(); }
    Authentication authenticate(String token, int expectedStatus) {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/admin/synthetic").header("Authorization","Bearer "+token));
        var found=new AtomicReference<Authentication>();
        filter.filter(exchange, ex -> ReactiveSecurityContextHolder.getContext().doOnNext(c -> found.set(c.getAuthentication())).then()).block();
        if(expectedStatus==401) assertEquals(401,exchange.getResponse().getStatusCode().value());
        else assertNotNull(found.get());
        return found.get();
    }
    @Test void passwordResetRevokesOldTokenAndNewLoginWorks() {
        var token=auth.login("synthetic_user","synthetic"+"Input2026").token(); authenticate(token,200);
        auth.resetPassword("1"+"9".repeat(10),"synthetic-code","replacement"+"Input2026");
        assertEquals(1,users.findById(id).getAuthVersion()); assertNull(authenticate(token,401));
        authenticate(auth.login("synthetic_user","replacement"+"Input2026").token(),200);
    }
    @Test void currentDatabaseRoleAndStatusOverrideSignedOldRole() {
        var token=jwt.generate(id,"synthetic_user","ADMIN",0);
        users.updateRole(id,"USER");
        var result=authenticate(token,200);
        assertTrue(result.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
        assertFalse(result.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        jdbc.update("update app_user set status=1 where id=?",id); assertNull(authenticate(token,401));
        jdbc.update("delete from app_user where id=?",id); assertNull(authenticate(token,401));
    }
    @Test void invalidTokenCannotSilentlyBecomeGuest() { assertNull(authenticate("synthetic-invalid-token",401)); }
    @Test void correctlySignedLegacyTokenWithoutVersionRequiresLoginAgain() {
        byte[] secret=new byte[48]; new java.security.SecureRandom().nextBytes(secret);
        var properties=new AuthProperties(); properties.setJwtSecret(java.util.Base64.getEncoder().encodeToString(secret));
        jwt=new JwtService(properties,new MockEnvironment()); filter=new JwtAuthFilter(jwt,users);
        String old=io.jsonwebtoken.Jwts.builder().subject(Long.toString(id)).claim("role","ADMIN")
                .expiration(new java.util.Date(System.currentTimeMillis()+60000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
        assertNull(authenticate(old,401));
    }
    @Test void anonymousRequestsStillWork() {
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/stats/home")),ex -> { called.set(true); return reactor.core.publisher.Mono.empty(); }).block();
        assertTrue(called.get());
    }
}
