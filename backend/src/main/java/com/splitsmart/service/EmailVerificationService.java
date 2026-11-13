package com.splitsmart.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;
import com.splitsmart.config.RecoveryEmailProperties;
import com.splitsmart.repository.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmailVerificationService {
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.context.ApplicationEventPublisher activityEvents = event -> {};
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final PasswordResetDelivery delivery;
    private final RecoveryEmailProperties emailSettings;
    private final Executor worker;
    private final TransactionTemplate tx;
    private final Clock clock;
    public EmailVerificationService(UserRepository users, JdbcTemplate jdbc, PasswordResetDelivery delivery,
            @Qualifier("passwordRecoveryExecutor") Executor worker, PlatformTransactionManager manager, Clock clock,
            RecoveryEmailProperties emailSettings) {
        this.users=users; this.jdbc=jdbc; this.delivery=delivery; this.worker=worker; this.emailSettings=emailSettings;
        this.tx=new TransactionTemplate(manager); this.clock=clock;
    }
    public void request(String subject) {
        if (!emailSettings.verificationEnabled() || !delivery.isConfigured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Email verification is currently unavailable. Use a private group link instead.");
        try { worker.execute(() -> {
            try {
                if (!delivery.isAvailable()) return;
                String token=SecureLinkTokens.create();
                var expiry=clock.instant().plusSeconds(1800);
                String recipient=tx.execute(status -> {
                    var user=users.findByIdForUpdate(Long.parseLong(subject)).orElseThrow();
                    if (user.isEmailVerified()) return null;
                    var now=LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
                    var recent=jdbc.queryForList("SELECT created_at FROM email_verification_tokens WHERE user_id=? AND created_at>? FOR UPDATE",
                            LocalDateTime.class, user.getId(), now.minusHours(1));
                    if (recent.size()>=3 || recent.stream().anyMatch(date -> date.plusSeconds(60).isAfter(now))) return null;
                    jdbc.update("INSERT INTO email_verification_tokens(token_hash,user_id,created_at,expires_at) VALUES(?,?,?,?)",
                            SecureLinkTokens.hash(token),user.getId(),now,LocalDateTime.ofInstant(expiry,ZoneOffset.UTC));
                    return user.getEmail();
                });
                if(recipient!=null) delivery.sendVerification(recipient,token,expiry);
            } catch(RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(EmailVerificationService.class).warn("Email verification processing failed.");
            }
        }); } catch(java.util.concurrent.RejectedExecutionException busy) { /* Generic accepted response, no credential disclosure. */ }
    }
    public void confirm(String subject,String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        tx.executeWithoutResult(status -> {
            var user=users.findByIdForUpdate(Long.parseLong(subject)).orElseThrow(EmailVerificationService::invalid);
            var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
            if(jdbc.update("UPDATE email_verification_tokens SET consumed_at=? WHERE token_hash=? AND user_id=? AND consumed_at IS NULL AND expires_at>?",
                    now,SecureLinkTokens.hash(token),user.getId(),now)!=1) throw invalid();
            jdbc.update("UPDATE users SET email_verified_at=? WHERE id=?",now,user.getId());
            activityEvents.publishEvent(new ActivityEvent("EMAIL_VERIFIED",null,user.getId(),null,"email-proof:"+user.getId()));
            jdbc.update("UPDATE email_verification_tokens SET consumed_at=? WHERE user_id=? AND consumed_at IS NULL",now,user.getId());
        });
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Verification link is invalid, expired, used, or belongs to another account."); }
}
