package com.splitsmart.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.splitsmart.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByEmail(String email);

	Optional<User> findByEmail(String email);

	@org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@org.springframework.data.jpa.repository.Query("select u from User u where u.id = :id")
	Optional<User> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
