package com.splitsmart;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.splitsmart.entity.*;
import com.splitsmart.repository.*;
import com.splitsmart.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
class InvitationSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired ExpenseGroupRepository groups;
    @Autowired GroupMemberRepository members;
    @Autowired InviteLinkService links;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired @Qualifier("passwordRecoveryExecutor") ThreadPoolTaskExecutor worker;
    @MockitoBean PasswordResetDelivery delivery;
    private User owner, recipient, outsider;
    private ExpenseGroup group;
    private final BlockingQueue<String> mailbox=new LinkedBlockingQueue<>();
    @BeforeEach void fixtures() {
        owner=user(); recipient=user(); outsider=user();
        group=groups.saveAndFlush(new ExpenseGroup("Invitation test",owner));
        members.saveAndFlush(new GroupMember(group,owner));
        when(delivery.isConfigured()).thenReturn(true); when(delivery.isAvailable()).thenReturn(true);
        doAnswer(call -> {mailbox.add(call.getArgument(1)); return null;}).when(delivery).sendVerification(anyString(),anyString(),any());
    }
    private User user(){return users.saveAndFlush(new User("Disposable",UUID.randomUUID()+"@example.invalid","{bcrypt}synthetic"));}
    @AfterEach void cleanup() throws Exception {
        worker.submit(()->{}).get(5,TimeUnit.SECONDS);
        if(group==null)return;
        for(var user:java.util.List.of(owner,recipient,outsider)) {
            jdbc.update("DELETE FROM notifications WHERE recipient_id=?",user.getId());
            jdbc.update("DELETE FROM email_verification_tokens WHERE user_id=?",user.getId());
        }
        jdbc.update("DELETE FROM group_invite_links WHERE group_id=?",group.getId());
        jdbc.update("DELETE FROM group_invitations WHERE group_id=?",group.getId());
        jdbc.update("DELETE FROM group_members WHERE group_id=?",group.getId());
        jdbc.update("DELETE FROM expense_groups WHERE id=?",group.getId());
        for(var user:java.util.List.of(owner,recipient,outsider)) jdbc.update("DELETE FROM users WHERE id=?",user.getId());
    }
    private String auth(User user) {
        return "Bearer "+encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("https://splitsmart.local").subject(user.getId().toString())
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build())).getTokenValue();
    }
    private String link() throws Exception {
        var result=mvc.perform(post("/api/groups/{id}/invite-links",group.getId()).header("Authorization",auth(owner)))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(result.getContentAsString()).get("token").asText();
    }
    private String body(String token)throws Exception{return json.writeValueAsString(Map.of("token",token));}
    @Test void directEnrollmentDisabledAndUnverifiedEmailDoesNotDiscloseInboxOrGrantMembership()throws Exception {
        mvc.perform(post("/api/groups/{id}/members",group.getId()).header("Authorization",auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email",recipient.getEmail())))).andExpect(status().isConflict());
        var created=mvc.perform(post("/api/groups/{id}/invitations",group.getId()).header("Authorization",auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email",recipient.getEmail()))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        long id=json.readTree(created.getContentAsString()).get("id").asLong();
        mvc.perform(get("/api/invitations").header("Authorization",auth(recipient))).andExpect(content().json("[]"));
        mvc.perform(post("/api/invitations/{id}/accept",id).header("Authorization",auth(recipient))).andExpect(status().isForbidden());
        assertFalse(members.existsByGroup_IdAndUser_Id(group.getId(),recipient.getId()));
        worker.submit(()->{}).get(5,TimeUnit.SECONDS);
    }
    @Test void mailboxProofIsUserBoundSingleUseAndAllowsExplicitInvitationAcceptance()throws Exception {
        var invitation=mvc.perform(post("/api/groups/{id}/invitations",group.getId()).header("Authorization",auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email",recipient.getEmail()))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        long id=json.readTree(invitation.getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/auth/email-verification").header("Authorization",auth(recipient))).andExpect(status().isAccepted());
        worker.submit(()->{}).get(5,TimeUnit.SECONDS);
        String token=mailbox.poll(2,TimeUnit.SECONDS); assertNotNull(token);
        mvc.perform(post("/api/auth/verify-email").header("Authorization",auth(outsider)).contentType(MediaType.APPLICATION_JSON).content(body(token))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/verify-email").header("Authorization",auth(recipient)).contentType(MediaType.APPLICATION_JSON).content(body(token))).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/verify-email").header("Authorization",auth(recipient)).contentType(MediaType.APPLICATION_JSON).content(body(token))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/invitations").header("Authorization",auth(recipient))).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(post("/api/invitations/{id}/accept",id).header("Authorization",auth(recipient))).andExpect(status().isNoContent());
        assertTrue(members.existsByGroup_IdAndUser_Id(group.getId(),recipient.getId()));
        mvc.perform(post("/api/invitations/{id}/accept",id).header("Authorization",auth(recipient))).andExpect(status().isConflict());
    }
    @Test void privateLinkRequiresConsentAndIsSingleUseWithoutAnEmailProvider()throws Exception {
        when(delivery.isConfigured()).thenReturn(false); when(delivery.isAvailable()).thenReturn(false);
        String token=link();
        assertFalse(members.existsByGroup_IdAndUser_Id(group.getId(),recipient.getId()));
        mvc.perform(post("/api/invitations/link-accept").contentType(MediaType.APPLICATION_JSON).content(body(token))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/invitations/link-accept").header("Authorization",auth(recipient)).contentType(MediaType.APPLICATION_JSON).content(body(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groupId").value(group.getId()));
        mvc.perform(post("/api/invitations/link-accept").header("Authorization",auth(outsider)).contentType(MediaType.APPLICATION_JSON).content(body(token))).andExpect(status().isNotFound());
        assertTrue(members.existsByGroup_IdAndUser_Id(group.getId(),recipient.getId()));
        assertFalse(members.existsByGroup_IdAndUser_Id(group.getId(),outsider.getId()));
    }
    @Test void onlyOwnerCanCreateInspectOrRevokeAndExpiredRevokedLinksFail()throws Exception {
        mvc.perform(post("/api/groups/{id}/invite-links",group.getId()).header("Authorization",auth(outsider))).andExpect(status().isNotFound());
        String token=link();
        Long id=jdbc.queryForObject("SELECT MAX(id) FROM group_invite_links WHERE group_id=?",Long.class,group.getId());
        mvc.perform(get("/api/groups/{id}/invite-links",group.getId()).header("Authorization",auth(outsider))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/groups/{g}/invite-links/{id}",group.getId(),id).header("Authorization",auth(outsider))).andExpect(status().isNotFound());
        links.revoke(group.getId(),id,owner.getId().toString());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->links.accept(token,recipient.getId().toString()));
        String expired=link(); jdbc.update("UPDATE group_invite_links SET expires_at=? WHERE group_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),group.getId());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->links.accept(expired,recipient.getId().toString()));
    }
    @Test void concurrentDifferentRecipientsCannotReuseOneLink()throws Exception {
        String token=link(); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();try{links.accept(token,recipient.getId().toString());return true;}catch(org.springframework.web.server.ResponseStatusException e){return false;}});
            var second=pool.submit(()->{start.await();try{links.accept(token,outsider.getId().toString());return true;}catch(org.springframework.web.server.ResponseStatusException e){return false;}});
            start.countDown(); assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
    }
    @Test void renewedExpiredEmailInvitationIsNotMisreportedAsDeclined()throws Exception {
        var request=json.writeValueAsString(Map.of("email",recipient.getEmail()));
        mvc.perform(post("/api/groups/{id}/invitations",group.getId()).header("Authorization",auth(owner)).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isCreated());
        jdbc.update("UPDATE group_invitations SET expires_at=? WHERE group_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),group.getId());
        mvc.perform(post("/api/groups/{id}/invitations",group.getId()).header("Authorization",auth(owner)).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isCreated());
        mvc.perform(get("/api/groups/{id}/invitations",group.getId()).header("Authorization",auth(owner)))
                .andExpect(jsonPath("$[0].status").value("PENDING")).andExpect(jsonPath("$[1].status").value("EXPIRED"));
    }
    @Test void mailboxProofCannotBeReusedAfterTheStoredAddressChanges() {
        jdbc.update("UPDATE users SET email_verified_at=CURRENT_TIMESTAMP WHERE id=?",recipient.getId());
        var account=users.findById(recipient.getId()).orElseThrow();
        assertTrue(account.isEmailVerified());
        account.setEmail(UUID.randomUUID()+"@example.invalid");
        users.saveAndFlush(account);
        assertFalse(users.findById(recipient.getId()).orElseThrow().isEmailVerified());
    }
}
