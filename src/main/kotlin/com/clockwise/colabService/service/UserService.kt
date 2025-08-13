package com.clockwise.colabService.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

@Service
class UserService(
    private val webClient: WebClient
) {
    
    @Value("\${user-service.base-url:http://user-service-local:8083}")
    private lateinit var userServiceBaseUrl: String

    /**
     * Get employee ID from Keycloak user ID
     */
    suspend fun getEmployeeIdFromKeycloakId(keycloakUserId: String): String? {
        return try {
            logger.debug { "Fetching employee ID for Keycloak user ID: $keycloakUserId" }
            
            val response = webClient
                .get()
                .uri("$userServiceBaseUrl/v1/users/keycloak/$keycloakUserId")
                .retrieve()
                .awaitBody<UserInfoResponse>()
            
            logger.debug { "Found employee ID: ${response.id} for Keycloak user ID: $keycloakUserId" }
            response.id
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch employee ID for Keycloak user ID: $keycloakUserId" }
            null
        }
    }

    /**
     * Get user information by employee ID
     */
    suspend fun getUserInfo(employeeId: String): UserInfoResponse? {
        return try {
            logger.debug { "Fetching user info for employee ID: $employeeId" }
            
            val response = webClient
                .get()
                .uri("$userServiceBaseUrl/v1/users/$employeeId")
                .retrieve()
                .awaitBody<UserInfoResponse>()
            
            logger.debug { "Found user info for employee ID: $employeeId" }
            response
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch user info for employee ID: $employeeId" }
            null
        }
    }
}

data class UserInfoResponse(
    val id: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val businessUnitId: String,
    val keycloakUserId: String?
)
