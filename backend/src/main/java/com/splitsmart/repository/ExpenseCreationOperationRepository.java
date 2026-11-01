package com.splitsmart.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import com.splitsmart.entity.ExpenseCreationOperation;

public interface ExpenseCreationOperationRepository extends JpaRepository<ExpenseCreationOperation, Long> {
    // A current read is required after waiting on the group lock under MySQL REPEATABLE READ.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ExpenseCreationOperation> findByGroupIdAndUserIdAndOperationKey(
            Long groupId, Long userId, String operationKey);
}
