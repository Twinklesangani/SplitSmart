package com.splitsmart.service;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.splitsmart.entity.GroupMember;
import com.splitsmart.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InviteLinkService {
    private final ExpenseGroupRepository groups;
    private final GroupMemberRepository members;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    public InviteLinkService(ExpenseGroupRepository groups, GroupMemberRepository members, UserRepository users, JdbcTemplate jdbc) {
        this.groups = groups; this.members = members; this.users = users; this.jdbc = jdbc;
    }
    public record Created(String token, Instant expiresAt) {
        @Override public String toString() { return "InviteLink[redacted]"; }
    }
    private com.splitsmart.entity.ExpenseGroup owner(Long groupId, String subject) {
        var group = groups.findByIdForUpdate(groupId).orElseThrow(InviteLinkService::missing);
        if (!Objects.equals(group.getCreatedBy().getId().toString(), subject)
                || !members.existsByGroup_IdAndUser_Id(groupId, Long.parseLong(subject))) throw missing();
        return group;
    }
    @Transactional
    public Created create(Long groupId, String subject) {
        owner(groupId, subject).requireActive();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM group_invite_links WHERE group_id=? AND created_at>?", Long.class,
                groupId, Timestamp.from(Instant.now().minusSeconds(86400)));
        if (count != null && count >= 20) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Invitation creation limit reached. Try later.");
        String token = SecureLinkTokens.create();
        Instant expiry = Instant.now().plusSeconds(7 * 86400);
        jdbc.update("INSERT INTO group_invite_links(group_id,token_hash,created_at,expires_at) VALUES(?,?,?,?)",
                groupId, SecureLinkTokens.hash(token), Timestamp.from(Instant.now()), Timestamp.from(expiry));
        return new Created(token, expiry);
    }
    @Transactional
    public List<Map<String, Object>> list(Long groupId, String subject) {
        owner(groupId, subject);
        return jdbc.query("SELECT id,expires_at,accepted_by,revoked_at FROM group_invite_links WHERE group_id=? ORDER BY id DESC LIMIT 50",
                (row, index) -> Map.<String, Object>of("id", row.getLong("id"), "expiresAt", row.getTimestamp("expires_at").toInstant(),
                        "status", row.getTimestamp("revoked_at") != null ? "REVOKED" : row.getObject("accepted_by") != null ? "ACCEPTED"
                                : row.getTimestamp("expires_at").toInstant().isAfter(Instant.now()) ? "PENDING" : "EXPIRED"), groupId);
    }
    @Transactional
    public void revoke(Long groupId, Long id, String subject) {
        owner(groupId, subject);
        if (jdbc.update("UPDATE group_invite_links SET revoked_at=? WHERE id=? AND group_id=? AND accepted_by IS NULL AND revoked_at IS NULL",
                Timestamp.from(Instant.now()), id, groupId) != 1) throw missing();
    }
    @Transactional
    public Long accept(String token, String subject) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw missing();
        String hash = SecureLinkTokens.hash(token);
        var ids = jdbc.queryForList("SELECT group_id FROM group_invite_links WHERE token_hash=?", Long.class, hash);
        if (ids.isEmpty()) throw missing();
        Long groupId = ids.getFirst();
        var group = groups.findByIdForUpdate(groupId).orElseThrow(InviteLinkService::missing);
        group.requireActive();
        Long userId = Long.parseLong(subject);
        var user = users.findById(userId).orElseThrow(InviteLinkService::missing);
        if (members.existsByGroup_IdAndUser_Id(groupId, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already belong to this group.");
        }
        if (jdbc.update("UPDATE group_invite_links SET accepted_by=? WHERE token_hash=? AND accepted_by IS NULL AND revoked_at IS NULL AND expires_at>?",
                userId, hash, Timestamp.from(Instant.now())) != 1) throw missing();
        members.saveAndFlush(new GroupMember(group, user));
        return groupId;
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation unavailable."); }
}
