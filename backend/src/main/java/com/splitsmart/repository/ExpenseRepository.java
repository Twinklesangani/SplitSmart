package com.splitsmart.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.splitsmart.dto.UserAmountTotal;
import com.splitsmart.entity.Expense;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

	long countByGroup_Id(Long groupId);

	List<Expense> findAllByGroup_Id(Long groupId);

	@Query("""
			select new com.splitsmart.dto.UserAmountTotal(expense.paidBy.id, sum(expense.amount))
			from Expense expense
			where expense.group.id = :groupId
			group by expense.paidBy.id
			""")
	List<UserAmountTotal> sumPaidByUserForGroup(@Param("groupId") Long groupId);

	@Query("""
			select distinct e from Expense e
			join fetch e.group
			join fetch e.paidBy
			join fetch e.createdBy
			left join fetch e.splits s
			left join fetch s.user
			where e.id = :expenseId and e.group.id = :groupId
			order by s.id asc
			""")
	java.util.Optional<Expense> findWithDetailsByIdAndGroupId(
			@Param("expenseId") Long expenseId,
			@Param("groupId") Long groupId);

	@Modifying(flushAutomatically = true)
	@Query("delete from Expense expense where expense.id = :expenseId and expense.group.id = :groupId")
	int deleteByIdAndGroupId(
			@Param("expenseId") Long expenseId,
			@Param("groupId") Long groupId);

	@Query("""
			select distinct e from Expense e
			join fetch e.paidBy
			join fetch e.createdBy
			left join fetch e.splits s
			left join fetch s.user
			where e.group.id = :groupId
			order by e.id desc, s.id asc
			""")
	List<Expense> findAllWithDetailsByGroupIdOrderByIdDesc(@Param("groupId") Long groupId);
}
