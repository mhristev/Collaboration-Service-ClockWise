package com.clockwise.colabService.listener

import com.clockwise.colabService.service.NotificationService
import com.clockwise.colabService.service.UserInfo
import com.clockwise.colabService.domain.Post
import com.clockwise.colabService.domain.ExchangeShift
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Response DTO for users by business unit request
 */
data class UsersByBusinessUnitResponse(
    val businessUnitId: String,
    val correlationId: String,
    val users: List<UserInfoDto>
)

data class UserInfoDto(
    val id: String,
    val fcmToken: String?,
    val firstName: String?,
    val lastName: String?,
    val role: String?
)

/**
 * Listener for users by business unit responses from User Service
 */
@Component
class UsersByBusinessUnitResponseListener(
    private val notificationService: NotificationService,
    private val objectMapper: ObjectMapper
) {

    // Store pending notification requests by correlation ID
    private val pendingNotifications = ConcurrentHashMap<String, PendingNotification>()

    @KafkaListener(topics = ["\${kafka.topic.users-by-business-unit-response}"], groupId = "collaboration-service")
    fun handleUsersByBusinessUnitResponse(message: String) {
        try {
            logger.info { "Received users by business unit response: $message" }
            val response = objectMapper.readValue(message, UsersByBusinessUnitResponse::class.java)
            
            val pendingNotification = pendingNotifications.remove(response.correlationId)
            if (pendingNotification == null) {
                logger.warn { "No pending notification found for correlation ID: ${response.correlationId}" }
                return
            }

            // Convert DTOs to domain objects
            val users = response.users.map { userDto ->
                UserInfo(
                    id = userDto.id,
                    fcmToken = userDto.fcmToken,
                    firstName = userDto.firstName,
                    lastName = userDto.lastName,
                    role = userDto.role
                )
            }

            logger.info { "Processing notification for ${users.size} users in business unit ${response.businessUnitId}" }

            // Send notifications asynchronously
            runBlocking {
                try {
                    when (pendingNotification.type) {
                        NotificationType.POST -> {
                            notificationService.sendPostNotificationToBusinessUnit(pendingNotification.post!!, users)
                        }
                        NotificationType.EXCHANGE_SHIFT -> {
                            notificationService.sendExchangeShiftNotificationToBusinessUnit(pendingNotification.exchangeShift!!, users)
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error sending notifications: ${e.message}", e)
                }
            }

        } catch (e: Exception) {
            logger.error("Error processing users by business unit response: ${e.message}", e)
        }
    }

    /**
     * Registers a pending notification that will be sent once users are received
     */
    fun registerPendingNotification(correlationId: String, post: Post) {
        pendingNotifications[correlationId] = PendingNotification(
            type = NotificationType.POST,
            post = post,
            exchangeShift = null
        )
        logger.debug { "Registered pending post notification for correlation ID: $correlationId" }
    }

    /**
     * Registers a pending exchange shift notification that will be sent once users are received
     */
    fun registerPendingExchangeShiftNotification(correlationId: String, exchangeShift: ExchangeShift) {
        pendingNotifications[correlationId] = PendingNotification(
            type = NotificationType.EXCHANGE_SHIFT,
            post = null,
            exchangeShift = exchangeShift
        )
        logger.debug { "Registered pending exchange shift notification for correlation ID: $correlationId" }
    }

    /**
     * Gets the count of pending notifications (for monitoring/testing)
     */
    fun getPendingNotificationCount(): Int = pendingNotifications.size
}

/**
 * Enum to differentiate between notification types
 */
enum class NotificationType {
    POST,
    EXCHANGE_SHIFT
}

/**
 * Internal data class to track pending notifications
 */
private data class PendingNotification(
    val type: NotificationType,
    val post: Post? = null,
    val exchangeShift: ExchangeShift? = null
)