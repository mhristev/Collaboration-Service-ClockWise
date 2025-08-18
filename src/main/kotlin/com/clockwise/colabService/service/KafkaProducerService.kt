package com.clockwise.colabService.service

import com.clockwise.colabService.dto.ShiftExchangeEventDto
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

@Service
class KafkaProducerService(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {
    
    @Value("\${kafka.topic.shift-exchange-events}")
    private lateinit var shiftExchangeTopic: String
    
    @Value("\${kafka.topic.shift-exchange-approval}")
    private lateinit var shiftExchangeApprovalTopic: String
    
    @Value("\${kafka.topic.users-by-business-unit-request}")
    private lateinit var usersRequestTopic: String
    
    @Value("\${kafka.topic.schedule-conflict-check-request}")
    private lateinit var scheduleConflictCheckRequestTopic: String
    
    @Value("\${kafka.topic.swap-conflict-check-request}")
    private lateinit var swapConflictCheckRequestTopic: String
    
    fun sendShiftExchangeApprovalEvent(event: ShiftExchangeEventDto): Mono<Void> {
        return Mono.fromCallable {
            val eventJson = objectMapper.writeValueAsString(event)
            logger.info { "Sending shift exchange approval event to topic $shiftExchangeApprovalTopic: $eventJson" }
            
            kafkaTemplate.send(shiftExchangeApprovalTopic, event.requestId, eventJson)
        }
        .doOnSuccess { 
            logger.info { "Successfully sent shift exchange approval event for request ${event.requestId}" }
        }
        .doOnError { error ->
            logger.error(error) { "Failed to send shift exchange approval event for request ${event.requestId}" }
        }
        .then()
    }
    
    /**
     * Requests users by business unit ID from the User Service
     */
    fun requestUsersByBusinessUnitId(businessUnitId: String, correlationId: String): Mono<Void> {
        return Mono.fromCallable {
            val request = UsersByBusinessUnitRequest(
                businessUnitId = businessUnitId,
                correlationId = correlationId
            )
            val requestJson = objectMapper.writeValueAsString(request)
            logger.info { "Requesting users for business unit $businessUnitId with correlation ID $correlationId" }
            
            kafkaTemplate.send(usersRequestTopic, businessUnitId, requestJson)
        }
        .doOnSuccess { 
            logger.info { "Successfully sent users request for business unit $businessUnitId" }
        }
        .doOnError { error ->
            logger.error(error) { "Failed to send users request for business unit $businessUnitId" }
        }
        .then()
    }
    
    /**
     * Send schedule conflict check request to Planning Service
     */
    fun sendScheduleConflictCheckRequest(requestJson: String, correlationId: String): Mono<Void> {
        return Mono.fromCallable {
            logger.info { "Sending schedule conflict check request with correlation ID $correlationId" }
            kafkaTemplate.send(scheduleConflictCheckRequestTopic, correlationId, requestJson)
        }
        .doOnSuccess { 
            logger.info { "Successfully sent schedule conflict check request for correlation ID $correlationId" }
        }
        .doOnError { error ->
            logger.error(error) { "Failed to send schedule conflict check request for correlation ID $correlationId" }
        }
        .then()
    }
    
    /**
     * Send swap conflict check request to Planning Service
     */
    fun sendSwapConflictCheckRequest(requestJson: String, correlationId: String): Mono<Void> {
        return Mono.fromCallable {
            logger.info { "Sending swap conflict check request with correlation ID $correlationId" }
            kafkaTemplate.send(swapConflictCheckRequestTopic, correlationId, requestJson)
        }
        .doOnSuccess { 
            logger.info { "Successfully sent swap conflict check request for correlation ID $correlationId" }
        }
        .doOnError { error ->
            logger.error(error) { "Failed to send swap conflict check request for correlation ID $correlationId" }
        }
        .then()
    }
}

/**
 * Request DTO for fetching users by business unit
 */
data class UsersByBusinessUnitRequest(
    val businessUnitId: String,
    val correlationId: String
)