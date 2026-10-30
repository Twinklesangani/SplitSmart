package com.splitsmart.entity;

import java.math.BigDecimal;

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
@Table(name = "expense_splits",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_expense_splits_expense_user",
				columnNames = { "expense_id", "user_id" }),
		indexes = @Index(name = "idx_expense_splits_user_id", columnList = "user_id"))
public class ExpenseSplit {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "expense_id", nullable = false)
	private Expense expense;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	protected ExpenseSplit() {
	}

	public ExpenseSplit(Expense expense, User user, BigDecimal amount) {
		this.expense = expense;
		this.user = user;
		this.amount = amount;
	}

	public Long getId() {
		return id;
	}

	public Expense getExpense() {
		return expense;
	}

	public User getUser() {
		return user;
	}

	public BigDecimal getAmount() {
		return amount;
	}
}
