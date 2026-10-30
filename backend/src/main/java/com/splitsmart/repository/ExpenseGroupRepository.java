package com.splitsmart.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.splitsmart.entity.ExpenseGroup;

public interface ExpenseGroupRepository extends JpaRepository<ExpenseGroup, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from ExpenseGroup g where g.id = :groupId")
	Optional<ExpenseGroup> findByIdForUpdate(@Param("groupId") Long groupId);

	@Query("""
			select g from ExpenseGroup g
			where exists (
				select m.id from GroupMember m
				where m.group = g and m.user.id = :userId
			)
			order by g.id desc
			""")
	List<ExpenseGroup> findAllForMemberOrderByIdDesc(@Param("userId") Long userId);
}
