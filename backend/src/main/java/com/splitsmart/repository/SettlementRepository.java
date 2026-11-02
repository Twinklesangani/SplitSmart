package com.splitsmart.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.splitsmart.dto.UserAmountTotal;
import com.splitsmart.entity.Settlement;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

	boolean existsByGroup_IdAndRequestId(Long groupId, String requestId);

	@Query("""
			select new com.splitsmart.dto.UserAmountTotal(settlement.fromUser.id, sum(settlement.amount))
			from Settlement settlement
			where settlement.group.id = :groupId
			group by settlement.fromUser.id
			""")
	List<UserAmountTotal> sumSentByUserForGroup(@Param("groupId") Long groupId);

	@Query("""
			select new com.splitsmart.dto.UserAmountTotal(settlement.toUser.id, sum(settlement.amount))
			from Settlement settlement
			where settlement.group.id = :groupId
			group by settlement.toUser.id
			""")
	List<UserAmountTotal> sumReceivedByUserForGroup(@Param("groupId") Long groupId);

	@Query("""
			select distinct settlement from Settlement settlement
			join fetch settlement.fromUser
			join fetch settlement.toUser
			join fetch settlement.recordedBy
			where settlement.group.id = :groupId
              and (:before is null or settlement.id < :before)
              and (:requestId is null or settlement.requestId = :requestId)
			order by settlement.id desc
			""")
	List<Settlement> findHistory(@Param("groupId") Long groupId, @Param("before") Long before,
            @Param("requestId") String requestId, org.springframework.data.domain.Pageable pageable);
}
