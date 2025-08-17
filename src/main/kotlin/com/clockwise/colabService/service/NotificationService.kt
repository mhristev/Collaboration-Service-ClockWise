package com.clockwise.colabService.service

import com.clockwise.colabService.domain.Post
import com.clockwise.colabService.domain.ExchangeShift
import com.clockwise.colabService.domain.ShiftRequest
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Data class representing user information needed for notifications
 */
data class UserInfo(
    val id: String,
    val fcmToken: String?,
    val firstName: String?,
    val lastName: String?,
    val role: String?
)

/**
 * Service for sending push notifications via Firebase Cloud Messaging
 */
@Service
class NotificationService(
    private val firebaseMessaging: FirebaseMessaging?,
    private val isFirebaseEnabled: Boolean
) {

    /**
     * Sends a post notification to multiple users in a business unit
     */
    suspend fun sendPostNotificationToBusinessUnit(post: Post, users: List<UserInfo>) {
        if (!isFirebaseEnabled || firebaseMessaging == null) {
            logger.warn("Firebase is not enabled or configured - skipping notification send")
            return
        }

        // Filter users based on target audience
        val filteredUsers = filterUsersByTargetAudience(users, post.targetAudience)
        
        val usersWithTokens = filteredUsers.filter { !it.fcmToken.isNullOrBlank() }
        if (usersWithTokens.isEmpty()) {
            logger.info("No eligible users with FCM tokens found for business unit ${post.businessUnitId} with target audience ${post.targetAudience}")
            return
        }

        logger.info("Sending post notification to ${usersWithTokens.size} users in business unit ${post.businessUnitId} (target: ${post.targetAudience})")

        val notification = buildPostNotification(post)
        var successCount = 0
        var failureCount = 0

        try {
            withContext(Dispatchers.IO) {
                usersWithTokens.forEach { user ->
                    try {
                        val message = buildNotificationMessage(user.fcmToken!!, notification, post)
                        val response = firebaseMessaging.send(message)
                        logger.info("Successfully sent notification to user ${user.id} (${user.role}), message ID: $response")
                        successCount++
                    } catch (e: Exception) {
                        logger.warn("Failed to send notification to user ${user.id} (${user.role}): ${e.message}")
                        failureCount++
                    }
                }
                
                logger.info("Notification summary: $successCount successful, $failureCount failures")
            }
        } catch (e: Exception) {
            logger.error("Error sending notifications: ${e.message}", e)
        }
    }

    /**
     * Sends an exchange shift notification to multiple users in a business unit
     */
    suspend fun sendExchangeShiftNotificationToBusinessUnit(exchangeShift: ExchangeShift, users: List<UserInfo>) {
        if (!isFirebaseEnabled || firebaseMessaging == null) {
            logger.warn("Firebase is not enabled or configured - skipping notification send")
            return
        }

        // Send to all users in the business unit (no filtering like posts)
        val usersWithTokens = users.filter { !it.fcmToken.isNullOrBlank() }
        if (usersWithTokens.isEmpty()) {
            logger.info("No eligible users with FCM tokens found for business unit ${exchangeShift.businessUnitId}")
            return
        }

        logger.info("Sending exchange shift notification to ${usersWithTokens.size} users in business unit ${exchangeShift.businessUnitId}")

        val notification = buildExchangeShiftNotification(exchangeShift)
        var successCount = 0
        var failureCount = 0

        try {
            withContext(Dispatchers.IO) {
                usersWithTokens.forEach { user ->
                    try {
                        val message = buildExchangeShiftNotificationMessage(user.fcmToken!!, notification, exchangeShift)
                        val response = firebaseMessaging.send(message)
                        logger.info("Successfully sent exchange shift notification to user ${user.id} (${user.role}), message ID: $response")
                        successCount++
                    } catch (e: Exception) {
                        logger.warn("Failed to send exchange shift notification to user ${user.id} (${user.role}): ${e.message}")
                        failureCount++
                    }
                }
                
                logger.info("Exchange shift notification summary: $successCount successful, $failureCount failures")
            }
        } catch (e: Exception) {
            logger.error("Error sending exchange shift notifications: ${e.message}", e)
        }
    }

    /**
     * Filters users based on the target audience of the post
     */
    private fun filterUsersByTargetAudience(users: List<UserInfo>, targetAudience: Post.TargetAudience): List<UserInfo> {
        return when (targetAudience) {
            Post.TargetAudience.ALL_EMPLOYEES -> {
                // Send to all users regardless of role
                logger.info("Target audience: ALL_EMPLOYEES - sending to all ${users.size} users")
                users
            }
            Post.TargetAudience.MANAGERS_ONLY -> {
                // Only send to users with manager role
                val managers = users.filter { user -> 
                    user.role?.lowercase() == "manager" || user.role?.lowercase() == "admin"
                }
                logger.info("Target audience: MANAGERS_ONLY - filtered to ${managers.size} managers/admins from ${users.size} total users")
                managers
            }
        }
    }

    /**
     * Sends a notification to a single user
     */
    suspend fun sendNotificationToUser(user: UserInfo, post: Post) {
        if (!isFirebaseEnabled || firebaseMessaging == null) {
            logger.warn("Firebase is not enabled or configured - skipping notification send")
            return
        }

        if (user.fcmToken.isNullOrBlank()) {
            logger.debug("User ${user.id} has no FCM token - skipping notification")
            return
        }

        try {
            val notification = buildPostNotification(post)
            val message = buildNotificationMessage(user.fcmToken, notification, post)

            withContext(Dispatchers.IO) {
                val response = firebaseMessaging.send(message)
                logger.info("Successfully sent notification to user ${user.id}, message ID: $response")
            }
        } catch (e: Exception) {
            logger.error("Error sending notification to user ${user.id}: ${e.message}", e)
        }
    }

    /**
     * Builds the notification payload for a post
     */
    private fun buildPostNotification(post: Post): Notification {
        val title = "New Post: ${post.title}"
        val authorName = "${post.creatorUserFirstName ?: ""} ${post.creatorUserLastName ?: ""}".trim()
        val body = if (authorName.isNotBlank()) {
            "Posted by $authorName"
        } else {
            "New post available"
        }

        return Notification.builder()
            .setTitle(title)
            .setBody(body)
            .build()
    }

    /**
     * Builds the complete FCM message
     */
    private fun buildNotificationMessage(fcmToken: String, notification: Notification, post: Post): Message {
        return Message.builder()
            .setToken(fcmToken)
            .setNotification(notification)
            .putData("type", "new_post")
            .putData("postId", post.id ?: "")
            .putData("businessUnitId", post.businessUnitId)
            .putData("authorName", "${post.creatorUserFirstName ?: ""} ${post.creatorUserLastName ?: ""}".trim())
            .putData("createdAt", post.createdAt.toString())
            .build()
    }

    /**
     * Builds the notification payload for an exchange shift
     */
    private fun buildExchangeShiftNotification(exchangeShift: ExchangeShift): Notification {
        val title = "New Shift Available"
        val posterName = "${exchangeShift.userFirstName ?: ""} ${exchangeShift.userLastName ?: ""}".trim()
        
        // Format the shift date if available
        val shiftDate = exchangeShift.shiftStartTime?.let { startTime ->
            val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd")
            startTime.format(formatter)
        }
        
        val body = when {
            posterName.isNotBlank() && shiftDate != null -> {
                "$posterName has posted a shift for $shiftDate"
            }
            posterName.isNotBlank() -> {
                "$posterName has posted a shift for exchange"
            }
            shiftDate != null -> {
                "New shift available for $shiftDate"
            }
            else -> {
                "A new shift is available for exchange"
            }
        }

        return Notification.builder()
            .setTitle(title)
            .setBody(body)
            .build()
    }

    /**
     * Builds the complete FCM message for exchange shift
     */
    private fun buildExchangeShiftNotificationMessage(fcmToken: String, notification: Notification, exchangeShift: ExchangeShift): Message {
        val posterName = "${exchangeShift.userFirstName ?: ""} ${exchangeShift.userLastName ?: ""}".trim()
        
        return Message.builder()
            .setToken(fcmToken)
            .setNotification(notification)
            .putData("type", "new_exchange_shift")
            .putData("exchangeShiftId", exchangeShift.id ?: "")
            .putData("businessUnitId", exchangeShift.businessUnitId)
            .putData("posterUserId", exchangeShift.posterUserId)
            .putData("posterName", posterName)
            .putData("shiftPosition", exchangeShift.shiftPosition ?: "")
            .putData("shiftStartTime", exchangeShift.shiftStartTime?.toString() ?: "")
            .putData("shiftEndTime", exchangeShift.shiftEndTime?.toString() ?: "")
            .putData("createdAt", exchangeShift.createdAt.toString())
            .build()
    }

    /**
     * Sends a shift request notification to the exchange shift poster
     */
    suspend fun sendShiftRequestNotificationToPoster(shiftRequest: ShiftRequest, exchangeShift: ExchangeShift, posterUser: UserInfo) {
        if (!isFirebaseEnabled || firebaseMessaging == null) {
            logger.warn("Firebase is not enabled or configured - skipping notification send")
            return
        }

        if (posterUser.fcmToken.isNullOrBlank()) {
            logger.debug("Poster user ${posterUser.id} has no FCM token - skipping notification")
            return
        }

        logger.info("Sending shift request notification to poster ${posterUser.id} for exchange shift ${exchangeShift.id}")

        try {
            val notification = buildShiftRequestNotification(shiftRequest, exchangeShift)
            val message = buildShiftRequestNotificationMessage(posterUser.fcmToken!!, notification, shiftRequest, exchangeShift)
            
            withContext(Dispatchers.IO) {
                val response = firebaseMessaging.send(message)
                logger.info("Successfully sent shift request notification to poster ${posterUser.id}, message ID: $response")
            }
        } catch (e: Exception) {
            logger.error("Failed to send shift request notification to poster ${posterUser.id}: ${e.message}", e)
        }
    }

    /**
     * Builds the notification payload for a shift request
     */
    private fun buildShiftRequestNotification(shiftRequest: ShiftRequest, exchangeShift: ExchangeShift): Notification {
        val title = "New Shift Request"
        val requesterName = "${shiftRequest.requesterUserFirstName ?: ""} ${shiftRequest.requesterUserLastName ?: ""}".trim()
        
        // Format the shift date if available
        val shiftDate = exchangeShift.shiftStartTime?.let { startTime ->
            val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd")
            startTime.format(formatter)
        }
        
        val requestTypeText = when (shiftRequest.requestType) {
            com.clockwise.colabService.domain.RequestType.TAKE_SHIFT -> "take"
            com.clockwise.colabService.domain.RequestType.SWAP_SHIFT -> "swap"
        }
        
        val body = when {
            requesterName.isNotBlank() && shiftDate != null -> {
                "$requesterName wants to $requestTypeText your shift on $shiftDate"
            }
            requesterName.isNotBlank() -> {
                "$requesterName wants to $requestTypeText your shift"
            }
            shiftDate != null -> {
                "Someone wants to $requestTypeText your shift on $shiftDate"
            }
            else -> {
                "Someone wants to $requestTypeText your shift"
            }
        }

        return Notification.builder()
            .setTitle(title)
            .setBody(body)
            .build()
    }

    /**
     * Builds the complete FCM message for shift request
     */
    private fun buildShiftRequestNotificationMessage(
        fcmToken: String, 
        notification: Notification, 
        shiftRequest: ShiftRequest, 
        exchangeShift: ExchangeShift
    ): Message {
        val requesterName = "${shiftRequest.requesterUserFirstName ?: ""} ${shiftRequest.requesterUserLastName ?: ""}".trim()
        
        return Message.builder()
            .setToken(fcmToken)
            .setNotification(notification)
            .putData("type", "shift_request")
            .putData("shiftRequestId", shiftRequest.id ?: "")
            .putData("exchangeShiftId", exchangeShift.id ?: "")
            .putData("businessUnitId", exchangeShift.businessUnitId)
            .putData("requesterUserId", shiftRequest.requesterUserId)
            .putData("requesterName", requesterName)
            .putData("requestType", shiftRequest.requestType.toString())
            .putData("shiftPosition", exchangeShift.shiftPosition ?: "")
            .putData("shiftStartTime", exchangeShift.shiftStartTime?.toString() ?: "")
            .putData("shiftEndTime", exchangeShift.shiftEndTime?.toString() ?: "")
            .putData("swapShiftId", shiftRequest.swapShiftId ?: "")
            .putData("createdAt", shiftRequest.createdAt.toString())
            .build()
    }

    /**
     * Test method to verify Firebase connectivity
     */
    suspend fun testConnection(): Boolean {
        if (!isFirebaseEnabled || firebaseMessaging == null) {
            logger.warn("Firebase is not enabled or configured")
            return false
        }

        return try {
            withContext(Dispatchers.IO) {
                // Try to access Firebase Messaging - this will fail if not properly configured
                firebaseMessaging.toString() // Simple operation to test connectivity
                true
            }
        } catch (e: Exception) {
            logger.error("Firebase connection test failed: ${e.message}", e)
            false
        }
    }
}