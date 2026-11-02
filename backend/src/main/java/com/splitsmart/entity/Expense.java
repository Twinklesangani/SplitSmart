package com.splitsmart.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "expenses", indexes = {
		@Index(name = "idx_expenses_group_id_id", columnList = "group_id, id"),
		@Index(name = "idx_expenses_paid_by_user_id", columnList = "paid_by_user_id"),
		@Index(name = "idx_expenses_created_by_user_id", columnList = "created_by_user_id")
})
public class Expense {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "group_id", nullable = false)
	private ExpenseGroup group;

	@Column(nullable = false, length = 255)
	private String description;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@Column(name = "currency_code", nullable = false, length = 3, columnDefinition = "CHAR(3)")
	private String currencyCode;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "paid_by_user_id", nullable = false)
	private User paidBy;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "created_by_user_id", nullable = false)
	private User createdBy;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private LocalDateTime createdAt;

	@OneToMany(mappedBy = "expense", fetch = FetchType.LAZY)
	private List<ExpenseSplit> splits = new ArrayList<>();

	protected Expense() {
	}

	public Expense(
			ExpenseGroup group,
			String description,
			BigDecimal amount,
			String currencyCode,
			User paidBy,
			User createdBy) {
		this.group = group;
		this.description = description;
		this.amount = amount;
		this.currencyCode = currencyCode;
		this.paidBy = paidBy;
		this.createdBy = createdBy;
	}

	public Long getId() {
		return id;
	}

	public ExpenseGroup getGroup() {
		return group;
	}

	public String getDescription() {
		return description;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrencyCode() {
		return currencyCode;
	}

	public User getPaidBy() {
		return paidBy;
	}

	public User getCreatedBy() {
		return createdBy;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public List<ExpenseSplit> getSplits() {
		return List.copyOf(splits);
	}

	public void updateDetails(String description, BigDecimal amount, User paidBy) {
		this.description = description;
		this.amount = amount;
		this.paidBy = paidBy;
	}

	public void replaceSplits(List<ExpenseSplit> splits) {
		this.splits.clear();
		this.splits.addAll(splits);
	}
}
