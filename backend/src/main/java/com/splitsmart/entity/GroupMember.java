package com.splitsmart.entity;

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
@Table(name = "group_members",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_group_members_group_user",
				columnNames = { "group_id", "user_id" }),
		indexes = @Index(name = "idx_group_members_user_id", columnList = "user_id"))
public class GroupMember {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "group_id", nullable = false)
	private ExpenseGroup group;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	protected GroupMember() {
	}

	public GroupMember(ExpenseGroup group, User user) {
		this.group = group;
		this.user = user;
	}

	public Long getId() {
		return id;
	}

	public ExpenseGroup getGroup() {
		return group;
	}

	public void setGroup(ExpenseGroup group) {
		this.group = group;
	}

	public User getUser() {
		return user;
	}

	public void setUser(User user) {
		this.user = user;
	}
}
