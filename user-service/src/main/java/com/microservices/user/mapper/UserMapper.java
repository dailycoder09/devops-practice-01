package com.microservices.user.mapper;

import com.microservices.user.dto.response.UserResponse;
import com.microservices.user.dto.response.UserSummaryResponse;
import com.microservices.user.model.User;
import org.mapstruct.Mapper;

/**
 * User Mapper Interface
 *
 * Uses MapStruct for mapping between the entity and the response DTO.
 * UserResponse has no password field, so the hash can never be copied by mistake.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Mapper(componentModel = "spring")
public interface UserMapper {

    /**
     * Maps User entity to UserResponse DTO
     *
     * @param user User entity
     * @return User response DTO
     */
    UserResponse toResponse(User user);

    /**
     * Maps User entity to the summary the admin list shows (no password hash)
     *
     * @param user User entity
     * @return User summary DTO
     */
    UserSummaryResponse toSummary(User user);
}
