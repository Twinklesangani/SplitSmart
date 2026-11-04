package com.splitsmart.service;

import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.splitsmart.repository.ExpenseGroupRepository;
import com.splitsmart.repository.GroupMemberRepository;

@Service
public class GroupManagementService {
    private final ExpenseGroupRepository groups;
    private final GroupMemberRepository members;
    private final ExpenseService expenses;
    private final JdbcTemplate jdbc;

    public GroupManagementService(ExpenseGroupRepository groups, GroupMemberRepository members,
            ExpenseService expenses, JdbcTemplate jdbc) {
        this.groups = groups;
        this.members = members;
        this.expenses = expenses;
        this.jdbc = jdbc;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void remove(Long groupId, Long targetId, String subject) {
        long requester = userId(subject);
        var group = groups.findByIdForUpdate(groupId).orElseThrow(GroupManagementService::notFound);
        if (!members.existsByGroup_IdAndUser_Id(groupId, requester)) throw notFound();
        group.requireActive();
        if (requester != targetId && !Objects.equals(group.getCreatedBy().getId(), requester)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the creator can remove another member.");
        }
        if (Objects.equals(group.getCreatedBy().getId(), targetId)) {
            throw conflict("The creator cannot leave or be removed. Archive a settled group instead.");
        }
        var balance = expenses.getGroupBalances(groupId, subject).balances().stream()
                .filter(member -> member.userId().equals(targetId)).findFirst().orElseThrow(GroupManagementService::notFound);
        if (balance.netBalance().signum() != 0) throw conflict("Settle the member's balance before leaving or removal.");
        Long history = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM expenses WHERE group_id = ? AND (paid_by_user_id = ? OR created_by_user_id = ?))
                     + (SELECT COUNT(*) FROM expense_splits s JOIN expenses e ON e.id = s.expense_id WHERE e.group_id = ? AND s.user_id = ?)
                     + (SELECT COUNT(*) FROM settlements WHERE group_id = ? AND (from_user_id = ? OR to_user_id = ? OR recorded_by_user_id = ?))
                """, Long.class, groupId, targetId, targetId, groupId, targetId, groupId, targetId, targetId, targetId);
        if (history == null || history > 0) throw conflict("Members in financial history are retained. Archive the settled group instead.");
        jdbc.update("DELETE FROM group_members WHERE group_id = ? AND user_id = ?", groupId, targetId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void archive(Long groupId, String subject) {
        long requester = userId(subject);
        var group = groups.findByIdForUpdate(groupId).orElseThrow(GroupManagementService::notFound);
        if (!members.existsByGroup_IdAndUser_Id(groupId, requester)) throw notFound();
        if (!Objects.equals(group.getCreatedBy().getId(), requester)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the creator can archive the group.");
        }
        if (expenses.getGroupBalances(groupId, subject).balances().stream().anyMatch(member -> member.netBalance().signum() != 0)) {
            throw conflict("Settle all balances before archiving the group.");
        }
        group.archive();
    }

    private static long userId(String subject) {
        try { return Long.parseLong(subject); }
        catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authenticated user.");
        }
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Group or member not found."); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
