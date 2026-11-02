package com.splitsmart.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "expense_creation_operations", uniqueConstraints = @UniqueConstraint(
        name = "uk_expense_creation_operation", columnNames = {"group_id", "user_id", "operation_key"}))
public class ExpenseCreationOperation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "group_id", nullable = false)
    private Long groupId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "operation_key", nullable = false, length = 36)
    private String operationKey;
    @Column(nullable = false, length = 64)
    private String fingerprint;
    @Column(name = "response_json", nullable = false, columnDefinition = "LONGTEXT")
    private String responseJson;

    protected ExpenseCreationOperation() {}
    public ExpenseCreationOperation(Long groupId, Long userId, String operationKey,
            String fingerprint, String responseJson) {
        this.groupId = groupId;
        this.userId = userId;
        this.operationKey = operationKey;
        this.fingerprint = fingerprint;
        this.responseJson = responseJson;
    }
    public String getFingerprint() { return fingerprint; }
    public String getResponseJson() { return responseJson; }
}
