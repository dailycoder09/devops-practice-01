package com.microservices.user.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * User Entity - an account that can log in
 *
 * The email is stored in lower case and is unique. Only a BCrypt hash of the
 * password is stored; the plain password is never saved, returned or logged.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "passwordHash")
@EqualsAndHashCode(of = "id")
public class User {

    /**
     * Unique identifier of the user (this is the "sub" claim of the token)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Email address, stored in lower case, unique
     */
    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    /**
     * BCrypt hash of the password
     */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /**
     * USER or ADMIN
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    /**
     * Timestamp when the user was created
     * Automatically populated by Hibernate
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp when the user was last updated
     * Automatically updated by Hibernate on each modification
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
