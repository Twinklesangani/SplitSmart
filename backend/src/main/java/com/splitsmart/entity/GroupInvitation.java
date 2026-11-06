package com.splitsmart.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "group_invitations")
public class GroupInvitation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private ExpenseGroup group;
    @Column(nullable = false, length = 255)
    private String email;
    @Column(nullable = false)
    private Instant expiresAt;
    @Column(nullable = false, length = 16)
    private String status;
    @Column(length = 1)
    private String activeSlot;
    @Column(name = "revoked_at")
    private Instant revokedAt;

    public void revoke() { resolve(false); revokedAt = Instant.now(); }
    public boolean isRevoked() { return revokedAt != null; }

    protected GroupInvitation() {}

    public GroupInvitation(ExpenseGroup group, String email, Instant expiresAt) {
        this.group = group;
        this.email = email;
        renew(expiresAt);
    }

    public Long getId() { return id; }
    public ExpenseGroup getGroup() { return group; }
    public String getEmail() { return email; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getStatus() { return status; }
    private void renew(Instant expiry) {
        expiresAt = expiry;
        status = "PENDING";
        activeSlot = "P";
    }

    public void resolve(boolean accept) {
        status = accept ? "ACCEPTED" : "DECLINED";
        activeSlot = null;
    }
}
