package com.splitsmart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.splitsmart.service.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Explicit opt-in only. No automatic retry; ledger survives JVM/test restarts. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("postgres-it")
@Import(ApprovedRecoveryEmailIT.Config.class)
class ApprovedRecoveryEmailIT {
    static final String EMAIL="twinklesangani@gmail.com";
    static final BlockingQueue<String> TOKENS=new LinkedBlockingQueue<>();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    static Path ledger() {
        if (!"reset1-change1-local-only".equals(System.getenv("SS_EMAIL_APPROVAL"))) throw new IllegalStateException("Explicit test approval missing");
        return Path.of(System.getenv("SS_EMAIL_LEDGER"));
    }
    static boolean identity(JdbcTemplate jdbc) {
        String expected=System.getenv("SS_SYNTHETIC_DATABASE");
        if (expected==null || !expected.startsWith("ss_pg_transfer_synthetic_")) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_database()=? AND host(inet_server_addr())='127.0.0.1' AND inet_server_port()=25432",Boolean.class,expected));
    }
    @TestConfiguration static class Config {
        @Bean @Primary PasswordResetDelivery controlledDelivery(JdbcTemplate jdbc) {
            if (!identity(jdbc)) throw new IllegalStateException("Synthetic database identity rejected");
            var http=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
            var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(java.time.Duration.ofSeconds(5));
            var transport=new BrevoTransactionalEmailClient(RestClient.builder().requestFactory(factory).build(),System.getenv("BREVO_API_KEY"),EMAIL);
            var gate=new ControlledRecoveryTransport(ledger(),()->identity(jdbc),transport);
            var actual=new EmailPasswordResetDelivery(gate,"https://localhost:15175");
            return new PasswordResetDelivery() {
                public boolean isAvailable(){return true;}
                public boolean isConfigured(){return true;}
                public void sendReset(String recipient,String token,Instant expiry){actual.sendReset(recipient,token,expiry);TOKENS.add(token);}
                public void sendPasswordChanged(String recipient){actual.sendPasswordChanged(recipient);}
            };
        }
    }
    int postJson(String path,Map<String,?> body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andReturn().getResponse().getStatus();
    }
    String login(String password) throws Exception {
        var response=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("email",EMAIL,"password",password)))).andReturn().getResponse();
        assertEquals(200,response.getStatus(),"Synthetic login failed");
        return json.readTree(response.getContentAsByteArray()).get("accessToken").asText();
    }
    List<String> financial() {
        var hashes=new ArrayList<String>();
        for(String table:List.of("expense_groups","group_members","expenses","expense_splits","settlements","expense_creation_operations"))
            hashes.add(jdbc.queryForObject("SELECT md5(coalesce(string_agg(row_to_json(t)::text,'' ORDER BY row_to_json(t)::text),'')) FROM "+table+" t",String.class));
        return hashes;
    }
    static void waitFor(Path marker,int seconds) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(!Files.exists(marker)) { if(System.nanoTime()>deadline) throw new IllegalStateException("Private inbox confirmation required");Thread.sleep(500); }
    }
    @Test void approvedTwoMessageRecovery() throws Exception {
        assertTrue(identity(jdbc));
        Path root=ledger();
        assertFalse(Files.exists(root.resolve("reset.attempt")),"Reset already attempted; do not replay");
        assertFalse(Files.exists(root.resolve("changed.attempt")),"Notification already attempted; do not replay");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM users WHERE email=?",Integer.class,EMAIL),"Recipient already exists in synthetic fixture; review without modifying it");
        String oldPassword=UUID.randomUUID()+"Aa7!";
        assertEquals(201,postJson("/api/auth/register",Map.of("name","Approved Recovery Synthetic","email",EMAIL,"password",oldPassword)));
        String oldJwt=login(oldPassword);
        List<String> before=financial();
        assertEquals(202,postJson("/api/auth/forgot-password",Map.of("email",EMAIL)));
        String token=TOKENS.poll(20,TimeUnit.SECONDS);
        assertNotNull(token,"Reset not accepted; no retry allowed");
        Files.createFile(root.resolve("reset.awaiting-inbox"));
        waitFor(root.resolve("reset.received"),1200);
        // The private browser driver receives secrets only through its inherited
        // environment; stdout/stderr are consumed, never published by Maven.
        var builder=new ProcessBuilder("node",System.getenv("SS_RECOVERY_BROWSER_DRIVER"));
        builder.directory(Path.of(System.getenv("SS_FRONTEND_DIRECTORY")).toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("BREVO_") || key.startsWith("DB_") || key.startsWith("PG_") || key.startsWith("SERVER_SSL_") || key.startsWith("JWT_"));
        builder.environment().put("SS_RECOVERY_TOKEN",token);
        builder.environment().put("SS_RECOVERY_OLD_PASSWORD",oldPassword);
        builder.environment().put("SS_RECOVERY_OLD_JWT",oldJwt);
        builder.redirectErrorStream(true);
        Process process=builder.start();
        if (!process.waitFor(90,TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Private browser timeout");
        }
        byte[] output=process.getInputStream().readAllBytes();
        assertEquals(0,process.exitValue(),"Private browser recovery failed; no email replay");
        assertTrue("RECOVERY_SECURITY_PASS".equals(new String(output,java.nio.charset.StandardCharsets.UTF_8).trim()),"Private browser result rejected");
        Arrays.fill(output,(byte)0);
        waitFor(root.resolve("changed.accepted"),20);
        assertEquals(before,financial(),"Synthetic financial reconciliation failed");
        Files.createFile(root.resolve("security-and-financial.pass"));
        waitFor(root.resolve("changed.received"),1200);
        Files.createFile(root.resolve("test.pass"));
    }
}
