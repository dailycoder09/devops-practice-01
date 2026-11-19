package com.microservices.user.repository;

import com.microservices.user.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * User Repository
 *
 * Emails are stored in lower case, so callers must pass a lower-case email.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Find a user by email (lower case)
     *
     * @param email Lower-case email
     * @return the user, if there is one
     */
    Optional<User> findByEmail(String email);

    /**
     * Tell whether an email is already used
     *
     * @param email Lower-case email
     * @return true if a user has this email
     */
    boolean existsByEmail(String email);

    /**
     * Find users whose email contains a text, in any case. The characters % and _ in the text are plain characters.
     *
     * @param text     the text to look for
     * @param pageable page and order
     * @return one page of users
     */
    Page<User> findByEmailContainingIgnoreCase(String text, Pageable pageable);
}
