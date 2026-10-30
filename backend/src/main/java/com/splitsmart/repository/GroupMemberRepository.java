package com.splitsmart.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.splitsmart.entity.GroupMember;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

	long countByGroup_IdAndUser_Id(Long groupId, Long userId);

	boolean existsByGroup_IdAndUser_Id(Long groupId, Long userId);

	@Query("""
			select m from GroupMember m
			join fetch m.user
			where m.group.id = :groupId
			order by m.id asc
			""")
	List<GroupMember> findAllWithUserByGroupIdOrderByIdAsc(@Param("groupId") Long groupId);
}
