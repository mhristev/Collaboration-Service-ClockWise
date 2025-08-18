package com.clockwise.colabService.listener

import com.clockwise.colabService.service.NotificationService
import com.clockwise.colabService.service.UserInfo
import com.clockwise.colabService.domain.Post
import com.clockwise.colabService.domain.ExchangeShift
import com.clockwise.colabService.domain.ShiftRequest
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
                            // Filter out the poster user - they shouldn't receive notification for their own exchange shift
                            val filteredUsers = users.filter { it.id != pendingNotification.exchangeShift!!.posterUserId }
                            logger.info { "Filtered out poster user ${pendingNotification.exchangeShift!!.posterUserId} from exchange shift notifications. Sending to ${filteredUsers.size} users instead of ${users.size}" }
                            notificationService.sendExchangeShiftNotificationToBusinessUnit(pendingNotification.exchangeShift!!, filteredUsers)
                        }
                        NotificationType.SHIFT_REQUEST -> {
                            // Find the poster user by poster user ID
                            val posterUser = users.find { it.id == pendingNotification.posterUserId }
                            if (posterUser != null) {
                                notificationService.sendShiftRequestNotificationToPoster(
                                    pendingNotification.shiftRequest!!, 
                                    pendingNotification.exchangeShift!!, 
                                    posterUser
                                )
                            } else {
                                logger.warn { "Poster user ${pendingNotification.posterUserId} not found in business unit ${response.businessUnitId}" }
                            }
                        }
                        NotificationType.MANAGER_APPROVAL -> {
                            // Send to all managers and admins in the business unit
                            notificationService.sendManagerApprovalNotification(
                                pendingNotification.exchangeShift!!, 
                                pendingNotification.shiftRequest!!, 
                                users
                            )
                        }
                        NotificationType.APPROVAL -> {
                            // Send approval notifications to both poster and requester
                            val posterUser = users.find { it.id == pendingNotification.exchangeShift!!.posterUserId }
                            val requesterUser = users.find { it.id == pendingNotification.shiftRequest!!.requesterUserId }
                            
                            if (posterUser != null) {
                                notificationService.sendApprovalNotificationToPoster(
                                    pendingNotification.exchangeShift!!, 
                                    pendingNotification.shiftRequest!!, 
                                    posterUser
                                )
                            } else {
                                logger.warn { "Poster user ${pendingNotification.exchangeShift!!.posterUserId} not found in business unit ${response.businessUnitId}" }
                            }
                            
                            if (requesterUser != null) {
                                notificationService.sendApprovalNotificationToRequester(
                                    pendingNotification.exchangeShift!!, 
                                    pendingNotification.shiftRequest!!, 
                                    requesterUser
                                )
                            } else {
                                logger.warn { "Requester user ${pendingNotification.shiftRequest!!.requesterUserId} not found in business unit ${response.businessUnitId}" }
                            }
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
     * Registers a pending shift request notification that will be sent once users are received
     */
    fun registerPendingShiftRequestNotification(
        correlationId: String, 
        shiftRequest: ShiftRequest, 
        exchangeShift: ExchangeShift
    ) {
        pendingNotifications[correlationId] = PendingNotification(
            type = NotificationType.SHIFT_REQUEST,
            post = null,
            exchangeShift = exchangeShift,
            shiftRequest = shiftRequest,
            posterUserId = exchangeShift.posterUserId
        )
        logger.debug { "Registered pending shift request notification for correlation ID: $correlationId" }
    }

    /**
     * Registers a pending manager approval notification that will be sent once users are received
     */
    fun registerPendingManagerApprovalNotification(
        correlationId: String, 
        shiftRequest: ShiftRequest, 
        exchangeShift: ExchangeShift
    ) {
        pendingNotifications[correlationId] = PendingNotification(
            type = NotificationType.MANAGER_APPROVAL,
            post = null,
            exchangeShift = exchangeShift,
            shiftRequest = shiftRequest,
            posterUserId = null
        )
        logger.debug { "Registered pending manager approval notification for correlation ID: $correlationId" }
    }

    /**
     * Registers a pending approval notification that will be sent to both poster and requester
     */
    fun registerPendingApprovalNotification(
        correlationId: String, 
        shiftRequest: ShiftRequest, 
        exchangeShift: ExchangeShift
    ) {
        pendingNotifications[correlationId] = PendingNotification(
            type = NotificationType.APPROVAL,
            post = null,
            exchangeShift = exchangeShift,
            shiftRequest = shiftRequest,
            posterUserId = null
        )
        logger.debug { "Registered pending approval notification for correlation ID: $correlationId" }
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
    EXCHANGE_SHIFT,
    SHIFT_REQUEST,
    MANAGER_APPROVAL,
    APPROVAL
}

/**
 * Internal data class to track pending notifications
 */
private data class PendingNotification(
    val type: NotificationType,
    val post: Post? = null,
    val exchangeShift: ExchangeShift? = null,
    val shiftRequest: ShiftRequest? = null,
    val posterUserId: String? = null
)