package com.clockwise.colabService.service

import com.clockwise.colabService.dto.*
import com.clockwise.colabService.domain.RequestType
import com.clockwise.colabService.repository.ShiftRequestRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Service
class ScheduleConflictService(
    private val kafkaProducerService: KafkaProducerService,
    private val objectMapper: ObjectMapper,
    private val shiftRequestRepository: ShiftRequestRepository
) {
    
    // Store pending conflict check requests - in production, use Redis or similar
    private val pendingTakeShiftChecks = ConcurrentHashMap<String, reactor.core.publisher.MonoSink<Boolean>>()
    private val pendingSwapShiftChecks = ConcurrentHashMap<String, reactor.core.publisher.MonoSink<Boolean>>()
    
    /**
     * Check if a TAKE_SHIFT request would create schedule conflicts for the requester
     */
    fun checkTakeShiftConflicts(
        requesterUserId: String,
        shiftStartTime: OffsetDateTime,
        shiftEndTime: OffsetDateTime
    ): Mono<Boolean> {
        val correlationId = UUID.randomUUID().toString()
        logger.info { "Checking TAKE_SHIFT conflicts for user $requesterUserId, correlation ID: $correlationId" }
        
        val request = ScheduleConflictCheckRequest(
            userId = requesterUserId,
            startTime = shiftStartTime,
            endTime = shiftEndTime,
            correlationId = correlationId
        )
        
        return sendScheduleConflictCheckRequest(request)
            .doOnSuccess { hasConflict ->
                logger.info { "TAKE_SHIFT conflict check result for user $requesterUserId: hasConflict=$hasConflict" }
            }
            .doOnError { error ->
                logger.error(error) { "Failed to check TAKE_SHIFT conflicts for user $requesterUserId" }
            }
    }
    
    /**
     * Check if a SWAP_SHIFT request would create schedule conflicts for both users
     */
    fun checkSwapShiftConflicts(
        posterUserId: String,
        requesterUserId: String,
        originalShiftId: String,
        swapShiftId: String
    ): Mono<Boolean> {
        val correlationId = UUID.randomUUID().toString()
        logger.info { "Checking SWAP_SHIFT conflicts between users $posterUserId and $requesterUserId, correlation ID: $correlationId" }
        
        val request = SwapConflictCheckRequest(
            posterUserId = posterUserId,
            requesterUserId = requesterUserId,
            originalShiftId = originalShiftId,
            swapShiftId = swapShiftId,
            correlationId = correlationId
        )
        
        return sendSwapConflictCheckRequest(request)
            .doOnSuccess { isSwapPossible ->
                logger.info { "SWAP_SHIFT conflict check result: isSwapPossible=$isSwapPossible" }
            }
            .doOnError { error ->
                logger.error(error) { "Failed to check SWAP_SHIFT conflicts" }
            }
    }
    
    /**
     * Send schedule conflict check request via Kafka
     */
    private fun sendScheduleConflictCheckRequest(request: ScheduleConflictCheckRequest): Mono<Boolean> {
        return Mono.create<Boolean> { sink ->
            val requestJson = objectMapper.writeValueAsString(request)
            logger.debug { "Sending schedule conflict check request: $requestJson" }
            
            // Store the pending request sink
            pendingTakeShiftChecks[request.correlationId] = sink
            
            kafkaProducerService.sendScheduleConflictCheckRequest(requestJson, request.correlationId)
                .subscribe(
                    { 
                        logger.debug { "Successfully sent conflict check request for ${request.correlationId}" } 
                    },
                    { error -> 
                        pendingTakeShiftChecks.remove(request.correlationId)
                        sink.error(error) 
                    }
                )
        }
        .timeout(java.time.Duration.ofSeconds(30))
        .onErrorReturn(true) // Default to has conflict if check fails (conservative approach)
    }
    
    /**
     * Send swap conflict check request via Kafka
     */
    private fun sendSwapConflictCheckRequest(request: SwapConflictCheckRequest): Mono<Boolean> {
        return Mono.create<Boolean> { sink ->
            val requestJson = objectMapper.writeValueAsString(request)
            logger.debug { "Sending swap conflict check request: $requestJson" }
            
            // Store the pending request sink
            pendingSwapShiftChecks[request.correlationId] = sink
            
            kafkaProducerService.sendSwapConflictCheckRequest(requestJson, request.correlationId)
                .subscribe(
                    { 
                        logger.debug { "Successfully sent swap conflict check request for ${request.correlationId}" } 
                    },
                    { error -> 
                        pendingSwapShiftChecks.remove(request.correlationId)
                        sink.error(error) 
                    }
                )
        }
        .timeout(java.time.Duration.ofSeconds(30))
        .onErrorReturn(false) // Default to swap not possible if check fails (conservative approach)
    }
    
    /**
     * Handle schedule conflict check response from Planning Service
     */
    fun handleScheduleConflictCheckResponse(response: ScheduleConflictCheckResponse) {
        logger.info { "Received schedule conflict check response for correlation ID: ${response.correlationId}" }
        
        pendingTakeShiftChecks.remove(response.correlationId)?.let { sink ->
            sink.success(!response.hasConflict) // Return true if NO conflict (execution is possible)
            logger.info { "Resolved conflict check: hasConflict=${response.hasConflict}, executionPossible=${!response.hasConflict}" }
        } ?: run {
            logger.warn { "Received response for unknown correlation ID: ${response.correlationId}" }
        }
    }
    
    /**
     * Handle swap conflict check response from Planning Service
     */
    fun handleSwapConflictCheckResponse(response: SwapConflictCheckResponse) {
        logger.info { "Received swap conflict check response for correlation ID: ${response.correlationId}" }
        
        pendingSwapShiftChecks.remove(response.correlationId)?.let { sink ->
            sink.success(response.isSwapPossible)
            logger.info { "Resolved swap conflict check: isSwapPossible=${response.isSwapPossible}" }
        } ?: run {
            logger.warn { "Received response for unknown correlation ID: ${response.correlationId}" }
        }
    }
    
    /**
     * Update shift request with conflict check result
     */
    @Transactional
    fun updateShiftRequestConflictStatus(requestId: String, isExecutionPossible: Boolean): Mono<Void> {
        logger.info { "Updating shift request $requestId with execution possibility: $isExecutionPossible" }
        
        return shiftRequestRepository.updateExecutionPossibility(requestId, isExecutionPossible)
            .doOnSuccess { updatedRows ->
                if (updatedRows > 0) {
                    logger.info { "Successfully updated shift request $requestId execution possibility (rows updated: $updatedRows)" }
                } else {
                    logger.warn { "No rows updated for shift request $requestId - request may not exist" }
                }
            }
            .doOnError { error ->
                logger.error(error) { "Failed to update shift request $requestId execution possibility" }
            }
            .then()
    }
}
