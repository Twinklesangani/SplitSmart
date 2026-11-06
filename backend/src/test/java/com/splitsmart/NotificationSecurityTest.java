package com.splitsmart;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.splitsmart.entity.*;
import com.splitsmart.repository.*;
import com.splitsmart.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
class NotificationSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired ExpenseGroupRepository groups;
    @Autowired GroupMemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired InviteLinkService links;
    @Autowired GroupManagementService management;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate tx;
    @Autowired com.splitsmart.config.NotificationMaintenance maintenance;
    private User owner,recipient,outsider;
    private ExpenseGroup group;
    @BeforeEach void fixtures() {
        owner=user();recipient=user();outsider=user();
        group=groups.saveAndFlush(new ExpenseGroup("Notification fixture",owner));
        members.saveAndFlush(new GroupMember(group,owner));members.saveAndFlush(new GroupMember(group,recipient));
    }
    private User user(){return users.saveAndFlush(new User("Disposable",UUID.randomUUID()+"@example.invalid","{bcrypt}synthetic"));}
    private String auth(User user){return "Bearer "+encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),JwtClaimsSet.builder().issuer("https://splitsmart.local").subject(user.getId().toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build())).getTokenValue();}
    private long expense()throws Exception {
        String request="{\"description\":\"Disposable dinner\",\"amount\":10,\"paidByUserId\":"+owner.getId()+",\"splitType\":\"EQUAL\",\"participantUserIds\":["+owner.getId()+","+recipient.getId()+"]}";
        var result=mvc.perform(post("/api/groups/{g}/expenses",group.getId()).header("Authorization",auth(owner)).header("Idempotency-Key","\""+UUID.randomUUID()+"\"").contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(result.getContentAsString()).get("id").asLong();
    }
    @AfterEach void cleanup() {
        for(var user:java.util.List.of(owner,recipient,outsider)) jdbc.update("DELETE FROM notifications WHERE recipient_id=?",user.getId());
        jdbc.update("DELETE FROM expense_creation_operations WHERE group_id=?",group.getId());
        jdbc.update("DELETE FROM settlements WHERE group_id=?",group.getId());
        jdbc.update("DELETE FROM expense_splits WHERE expense_id IN(SELECT id FROM expenses WHERE group_id=?)",group.getId());
        jdbc.update("DELETE FROM expenses WHERE group_id=?",group.getId());
        jdbc.update("DELETE FROM group_invite_links WHERE group_id=?",group.getId());
        jdbc.update("DELETE FROM group_invitations WHERE group_id=?",group.getId());
        jdbc.update("DELETE FROM group_members WHERE group_id=?",group.getId());
        jdbc.update("DELETE FROM expense_groups WHERE id=?",group.getId());
        for(var user:java.util.List.of(owner,recipient,outsider)) jdbc.update("DELETE FROM users WHERE id=?",user.getId());
    }
    @Test void expensesAndRepaymentsCreateRecipientScopedAuthorizedNavigation()throws Exception {
        long id=expense();
        mvc.perform(get("/api/notifications").header("Authorization",auth(recipient))).andExpect(jsonPath("$.unreadCount").value(1)).andExpect(jsonPath("$.items[0].href").value("/groups/"+group.getId()+"/expenses/"+id));
        mvc.perform(get("/api/notifications").header("Authorization",auth(owner))).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/notifications").header("Authorization",auth(outsider))).andExpect(jsonPath("$.unreadCount").value(0));
        mvc.perform(post("/api/groups/{g}/settlements",group.getId()).header("Authorization",auth(recipient)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fromUserId",recipient.getId(),"toUserId",owner.getId(),"amount","5.00","requestId",UUID.randomUUID().toString())))).andExpect(status().isCreated());
        mvc.perform(get("/api/notifications").header("Authorization",auth(owner))).andExpect(jsonPath("$.items[0].type").value("SETTLEMENT"));
    }
    @Test void readUnreadAndCountsRejectOtherUsersAndRevokedGroupAccess()throws Exception {
        expense();
        Long id=jdbc.queryForObject("SELECT id FROM notifications WHERE recipient_id=?",Long.class,recipient.getId());
        mvc.perform(put("/api/notifications/{id}",id).header("Authorization",auth(outsider)).contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}")).andExpect(status().isNotFound());
        for(boolean read:new boolean[]{true,false}) {
            mvc.perform(put("/api/notifications/{id}",id).header("Authorization",auth(recipient)).contentType(MediaType.APPLICATION_JSON).content("{\"read\":"+read+"}")).andExpect(status().isNoContent());
            mvc.perform(get("/api/notifications/unread-count").header("Authorization",auth(recipient))).andExpect(jsonPath("$.count").value(read?0:1));
        }
        jdbc.update("DELETE FROM group_members WHERE group_id=? AND user_id=?",group.getId(),recipient.getId());
        mvc.perform(get("/api/notifications").header("Authorization",auth(recipient))).andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.unreadCount").value(0));
        mvc.perform(put("/api/notifications/{id}",id).header("Authorization",auth(recipient)).contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}")).andExpect(status().isNotFound());
    }
    @Test void joiningAndRemovalNotifyMembersWithoutLeakingFormerGroupDetails()throws Exception {
        var link=links.create(group.getId(),owner.getId().toString());
        links.accept(link.token(),outsider.getId().toString());
        mvc.perform(get("/api/notifications").header("Authorization",auth(owner))).andExpect(jsonPath("$.items[0].type").value("MEMBERSHIP"));
        management.remove(group.getId(),outsider.getId(),owner.getId().toString());
        mvc.perform(get("/api/notifications").header("Authorization",auth(outsider))).andExpect(jsonPath("$.items[0].href").value("/groups"));
    }
    @Test void mailboxVerifiedInvitationIsDeduplicatedAndRollbacksLeaveNoGhosts()throws Exception {
        jdbc.update("DELETE FROM group_members WHERE group_id=? AND user_id=?",group.getId(),recipient.getId());
        mvc.perform(post("/api/groups/{g}/invitations",group.getId()).header("Authorization",auth(owner)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email",recipient.getEmail())))).andExpect(status().isCreated());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id=?",Integer.class,recipient.getId()));
        jdbc.update("UPDATE users SET email_verified_at=CURRENT_TIMESTAMP WHERE id=?",recipient.getId());
        tx.executeWithoutResult(status->{
            events.publishEvent(new ActivityEvent("EMAIL_VERIFIED",null,recipient.getId(),null,"proof"));
            events.publishEvent(new ActivityEvent("EMAIL_VERIFIED",null,recipient.getId(),null,"proof"));
        });
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id=?",Integer.class,recipient.getId()));
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{
            events.publishEvent(new ActivityEvent("EXPENSE",group.getId(),recipient.getId(),1L,"rolled-back"));
            throw new IllegalStateException("Synthetic rollback");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_key='rolled-back'",Integer.class));
    }
    @Test void retentionPaginationAndUnauthenticatedBoundariesAreBounded()throws Exception {
        var now=LocalDateTime.now(ZoneOffset.UTC);
        for(int i=0;i<202;i++) jdbc.update("INSERT INTO notifications(recipient_id,event_key,type,title,created_at) VALUES(?,?,?,?,?)",recipient.getId(),"seed:"+i,"INVITATION","Synthetic notification",i==0?now.minusDays(91):now);
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications?limit=0").header("Authorization",auth(recipient))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/notifications?limit=10").header("Authorization",auth(recipient))).andExpect(jsonPath("$.items.length()").value(10)).andExpect(jsonPath("$.unreadCount").value(200)).andExpect(jsonPath("$.nextBefore").isNumber());
        assertEquals(200,jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id=?",Integer.class,recipient.getId()));
    }
    @Test void maintenanceExpiresInactiveRecipientsWithoutTouchingRecentUpdates() {
        var now=LocalDateTime.now(ZoneOffset.UTC);
        for(int i=0;i<2;i++) jdbc.update("INSERT INTO notifications(recipient_id,event_key,type,title,created_at) VALUES(?,?,?,?,?)",
                recipient.getId(),"maintenance:"+i,"INVITATION","Synthetic notification",i==0?now.minusDays(91):now);
        maintenance.cleanup();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id=?",Integer.class,recipient.getId()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM expense_groups WHERE id=?",Integer.class,group.getId()));
    }
}
