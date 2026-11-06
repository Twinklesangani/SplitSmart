package com.splitsmart.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.splitsmart.entity.GroupInvitation;

public interface GroupInvitationRepository extends JpaRepository<GroupInvitation, Long> {
    List<GroupInvitation> findByGroup_IdOrderByIdDesc(Long groupId, org.springframework.data.domain.Pageable page);
    long countByGroup_IdAndStatusAndExpiresAtAfter(Long groupId, String status, Instant now);
    Optional<GroupInvitation> findByGroup_IdAndEmailAndActiveSlot(Long groupId, String email, String activeSlot);

    @Query("select i from GroupInvitation i join fetch i.group where i.email = :email and i.status = 'PENDING' and i.expiresAt > :now and i.group.archived = false order by i.id desc")
    List<GroupInvitation> findPending(@Param("email") String email, @Param("now") Instant now);
}
