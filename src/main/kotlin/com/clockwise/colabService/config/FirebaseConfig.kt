package com.clockwise.colabService.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import javax.annotation.PostConstruct

private val logger = KotlinLogging.logger {}

/**
 * Firebase configuration for push notifications.
 * Initializes Firebase Admin SDK for sending FCM messages.
 */
@Configuration
class FirebaseConfig {

    @Value("\${firebase.admin.credentials.path:classpath:clockwise-firebase-adminsdk.json}")
    private lateinit var credentialsPath: Resource

    @Value("\${firebase.project.id:clockwise-mobile-app}")
    private lateinit var projectId: String

    @Value("\${firebase.notifications.enabled:true}")
    private var notificationsEnabled: Boolean = true

    @PostConstruct
    fun initialize() {
        try {
            if (!notificationsEnabled) {
                logger.info("Firebase notifications are disabled")
                return
            }

            if (FirebaseApp.getApps().isEmpty()) {
                val credentials = GoogleCredentials.fromStream(credentialsPath.inputStream)
                val options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setProjectId(projectId)
                    .build()

                FirebaseApp.initializeApp(options)
                logger.info("Firebase initialized successfully for project: $projectId")
            } else {
                logger.info("Firebase app already initialized")
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize Firebase: ${e.message}", e)
            // Don't throw exception to allow service to start without Firebase in development
            logger.warn("Service will continue without push notification capabilities")
        }
    }

    @Bean
    fun firebaseMessaging(): FirebaseMessaging? {
        return try {
            if (!notificationsEnabled) {
                logger.info("Firebase messaging bean not created - notifications disabled")
                return null
            }
            
            if (FirebaseApp.getApps().isNotEmpty()) {
                FirebaseMessaging.getInstance()
            } else {
                logger.warn("Firebase not initialized - messaging bean not available")
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to create Firebase Messaging bean: ${e.message}", e)
            null
        }
    }

    @Bean
    fun isFirebaseEnabled(): Boolean = notificationsEnabled && FirebaseApp.getApps().isNotEmpty()
}