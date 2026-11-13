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
import com.splitsmart.config.RecoveryEmailProperties;
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
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.context.ApplicationEventPublisher activityEvents = event -> {};
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final UserRepository users;
    private final ExpenseGroupRepository groups;
    private final GroupMemberRepository members;
    private final GroupInvitationRepository invitations;
    @org.springframework.beans.factory.annotation.Autowired private PasswordResetDelivery delivery;
    @org.springframework.beans.factory.annotation.Autowired private RecoveryEmailProperties emailSettings;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("passwordRecoveryExecutor") private java.util.concurrent.Executor mailWorker;

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
        group.requireActive();
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
        if(invitations.countByGroup_IdAndStatusAndExpiresAtAfter(groupId,"PENDING",now)>=50) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many pending invitations.");
        }
        var existing = invitations.findByGroup_IdAndEmailAndActiveSlot(groupId, request.email(), "P");
        if (existing.isPresent() && "PENDING".equals(existing.get().getStatus())
                && existing.get().getExpiresAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An active invitation already exists.");
        }
        existing.ifPresent(GroupInvitation::expire);
        invitations.flush();
        GroupInvitation invitation = new GroupInvitation(group, request.email(), now.plus(Duration.ofDays(7)));
        var saved=invitations.saveAndFlush(invitation);
        activityEvents.publishEvent(new ActivityEvent("INVITATION",groupId,owner.getId(),saved.getId(),"invitation:"+saved.getId()));
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        try { mailWorker.execute(() -> {
                            if(!emailSettings.invitationsEnabled() || !delivery.isAvailable()) return;
                            try { delivery.sendInvitation(saved.getEmail(), group.getName()); }
                            catch(RuntimeException ignored) { org.slf4j.LoggerFactory.getLogger(InvitationService.class).warn("Invitation email processing failed."); }
                        }); } catch(java.util.concurrent.RejectedExecutionException ignored) { /* In-app invitation remains available. */ }
                    }
                });
        return response(saved);
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> inbox(String subject) {
        var recipient=user(subject);
        if(!recipient.isEmailVerified()) return List.of();
        return invitations.findPending(recipient.getEmail(), Instant.now()).stream()
                .map(InvitationService::response).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void respond(Long id, String subject, boolean accept) {
        User recipient = user(subject);
        var invitation = invitations.findById(id).orElseThrow(InvitationService::notFound);
        if (!recipient.getEmail().equals(invitation.getEmail())) throw notFound();
        if(accept && !recipient.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Verify your email ownership before accepting email invitations. A private group link can be used without email verification.");
        }
        Long groupId = invitation.getGroup().getId();
        groups.findByIdForUpdate(groupId).orElseThrow(InvitationService::notFound).requireActive();
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
        if(accept) activityEvents.publishEvent(new ActivityEvent("MEMBER_JOINED",groupId,recipient.getId(),recipient.getId(),"join-email:"+invitation.getId()));
    }

    @Transactional
    public List<java.util.Map<String,Object>> sent(Long groupId,String subject) {
        var group=groups.findByIdForUpdate(groupId).orElseThrow(InvitationService::notFound);
        if(!group.getCreatedBy().getId().toString().equals(subject)
                || !members.existsByGroup_IdAndUser_Id(groupId,Long.parseLong(subject))) throw notFound();
        return invitations.findByGroup_IdOrderByIdDesc(groupId,org.springframework.data.domain.PageRequest.of(0,50)).stream()
                .map(i -> java.util.Map.<String,Object>of("id",i.getId(),"email",i.getEmail(),"expiresAt",i.getExpiresAt(),
                        "status",i.isRevoked()?"REVOKED":"PENDING".equals(i.getStatus())&&!i.getExpiresAt().isAfter(Instant.now())?"EXPIRED":i.getStatus())).toList();
    }
    @Transactional
    public void revoke(Long groupId,Long id,String subject) {
        var group=groups.findByIdForUpdate(groupId).orElseThrow(InvitationService::notFound);
        if(!group.getCreatedBy().getId().toString().equals(subject)
                || !members.existsByGroup_IdAndUser_Id(groupId,Long.parseLong(subject))) throw notFound();
        var invitation=invitations.findById(id).orElseThrow(InvitationService::notFound);
        entityManager.refresh(invitation,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(!invitation.getGroup().getId().equals(groupId)||!"PENDING".equals(invitation.getStatus())) throw notFound();
        invitation.revoke();
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
