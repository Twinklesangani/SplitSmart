package com.splitsmart.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;
import com.splitsmart.dto.AddGroupMemberRequest;
import com.splitsmart.dto.InvitationResponse;
import com.splitsmart.entity.GroupInvitation;
import com.splitsmart.entity.GroupMember;
import com.splitsmart.entity.User;
import com.splitsmart.repository.ExpenseGroupRepository;
import com.splitsmart.repository.GroupInvitationRepository;
import com.splitsmart.repository.GroupMemberRepository;
import com.splitsmart.repository.UserRepository;

@Service
public class InvitationService {
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final UserRepository users;
    private final ExpenseGroupRepository groups;
    private final GroupMemberRepository members;
    private final GroupInvitationRepository invitations;

    public InvitationService(UserRepository users, ExpenseGroupRepository groups,
            GroupMemberRepository members, GroupInvitationRepository invitations) {
        this.users = users;
        this.groups = groups;
        this.members = members;
        this.invitations = invitations;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InvitationResponse invite(Long groupId, String subject, AddGroupMemberRequest request) {
        User owner = user(subject);
        // The group lock serializes invitation creation and acceptance for this group.
        var group = groups.findByIdForUpdate(groupId).orElseThrow(InvitationService::notFound);
        if (!Objects.equals(group.getCreatedBy().getId(), owner.getId())
                || !members.existsByGroup_IdAndUser_Id(groupId, owner.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found.");
        }
        users.findByEmail(request.email()).ifPresent(recipient -> {
            if (members.existsByGroup_IdAndUser_Id(groupId, recipient.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Recipient is already a member.");
            }
        });
        Instant now = Instant.now();
        var existing = invitations.findByGroup_IdAndEmailAndActiveSlot(groupId, request.email(), "P");
        if (existing.isPresent() && "PENDING".equals(existing.get().getStatus())
                && existing.get().getExpiresAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An active invitation already exists.");
        }
        existing.ifPresent(expired -> expired.resolve(false));
        invitations.flush();
        GroupInvitation invitation = new GroupInvitation(group, request.email(), now.plus(Duration.ofDays(7)));
        return response(invitations.saveAndFlush(invitation));
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> inbox(String subject) {
        return invitations.findPending(user(subject).getEmail(), Instant.now()).stream()
                .map(InvitationService::response).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void respond(Long id, String subject, boolean accept) {
        User recipient = user(subject);
        var invitation = invitations.findById(id).orElseThrow(InvitationService::notFound);
        if (!recipient.getEmail().equals(invitation.getEmail())) throw notFound();
        Long groupId = invitation.getGroup().getId();
        groups.findByIdForUpdate(groupId).orElseThrow(InvitationService::notFound);
        entityManager.refresh(invitation, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        resolveLocked(invitation, groupId, recipient, accept);
    }

    private void resolveLocked(GroupInvitation invitation, Long groupId, User recipient, boolean accept) {
        if (!"PENDING".equals(invitation.getStatus()) || !invitation.getExpiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invitation expired or already resolved.");
        }
        if (accept && !members.existsByGroup_IdAndUser_Id(groupId, recipient.getId())) {
            members.saveAndFlush(new GroupMember(invitation.getGroup(), recipient));
        }
        invitation.resolve(accept);
    }

    private User user(String subject) {
        try {
            return users.findById(Long.parseLong(subject)).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authenticated user."));
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authenticated user.");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found.");
    }

    private static InvitationResponse response(GroupInvitation invitation) {
        return new InvitationResponse(invitation.getId(), invitation.getGroup().getId(),
                invitation.getGroup().getName(), invitation.getExpiresAt());
    }
}
