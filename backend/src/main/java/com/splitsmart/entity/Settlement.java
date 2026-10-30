package com.splitsmart.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "settlements",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_settlements_group_request",
				columnNames = { "group_id", "request_id" }),
		indexes = {
				@Index(name = "idx_settlements_group_id_id", columnList = "group_id, id"),
				@Index(name = "idx_settlements_from_user_id", columnList = "from_user_id"),
				@Index(name = "idx_settlements_to_user_id", columnList = "to_user_id"),
				@Index(name = "idx_settlements_recorded_by_user_id", columnList = "recorded_by_user_id")
		})
public class Settlement {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "group_id", nullable = false)
	private ExpenseGroup group;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "from_user_id", nullable = false)
	private User fromUser;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "to_user_id", nullable = false)
	private User toUser;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "recorded_by_user_id", nullable = false)
	private User recordedBy;

	@Column(name = "request_id", nullable = false, length = 36)
	private String requestId;

	@Column(length = 200)
	private String note;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private LocalDateTime createdAt;

	protected Settlement() {
	}

	public Settlement(
			ExpenseGroup group,
			User fromUser,
			User toUser,
			BigDecimal amount,
			User recordedBy,
			String requestId,
			String note) {
		this.group = group;
		this.fromUser = fromUser;
		this.toUser = toUser;
		this.amount = amount;
		this.recordedBy = recordedBy;
		this.requestId = requestId;
		this.note = note;
	}

	public Long getId() {
		return id;
	}

	public ExpenseGroup getGroup() {
		return group;
	}

	public User getFromUser() {
		return fromUser;
	}

	public User getToUser() {
		return toUser;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public User getRecordedBy() {
		return recordedBy;
	}

	public String getRequestId() {
		return requestId;
	}

	public String getNote() {
		return note;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
