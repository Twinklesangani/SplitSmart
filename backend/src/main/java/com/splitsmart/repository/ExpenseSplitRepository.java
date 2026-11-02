package com.splitsmart.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.NonNull;

import com.splitsmart.dto.UserAmountTotal;
import com.splitsmart.entity.ExpenseSplit;

public interface ExpenseSplitRepository extends JpaRepository<ExpenseSplit, Long> {

	long countByExpense_Id(Long expenseId);

	long countByExpense_Group_Id(Long groupId);

	@NonNull
	java.util.List<ExpenseSplit> findAllByExpense_Id(Long expenseId);

	@Query("""
			select new com.splitsmart.dto.UserAmountTotal(split.user.id, sum(split.amount))
			from ExpenseSplit split
			where split.expense.group.id = :groupId
			group by split.user.id
			""")
	java.util.List<UserAmountTotal> sumSharesByUserForGroup(@Param("groupId") Long groupId);

	@Modifying(flushAutomatically = true)
	@Query("delete from ExpenseSplit split where split.expense.id = :expenseId")
	int deleteAllByExpenseId(@Param("expenseId") Long expenseId);
}
