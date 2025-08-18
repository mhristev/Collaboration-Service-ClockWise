package com.clockwise.colabService.listener

import com.clockwise.colabService.dto.ScheduleConflictCheckResponse
import com.clockwise.colabService.dto.SwapConflictCheckResponse
import com.clockwise.colabService.service.ScheduleConflictService
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class ConflictCheckResponseListener(
    private val scheduleConflictService: ScheduleConflictService,
    private val objectMapper: ObjectMapper
) {
    
    @KafkaListener(
        topics = ["\${kafka.topic.schedule-conflict-check-response}"],
        groupId = "collaboration-service"
    )
    fun handleScheduleConflictCheckResponse(message: String) {
        try {
            logger.info { "Received schedule conflict check response: $message" }
            val response = objectMapper.readValue(message, ScheduleConflictCheckResponse::class.java)
            scheduleConflictService.handleScheduleConflictCheckResponse(response)
        } catch (e: Exception) {
            logger.error(e) { "Failed to process schedule conflict check response: $message" }
        }
    }
    
    @KafkaListener(
        topics = ["\${kafka.topic.swap-conflict-check-response}"],
        groupId = "collaboration-service"
    )
    fun handleSwapConflictCheckResponse(message: String) {
        try {
            logger.info { "Received swap conflict check response: $message" }
            val response = objectMapper.readValue(message, SwapConflictCheckResponse::class.java)
            scheduleConflictService.handleSwapConflictCheckResponse(response)
        } catch (e: Exception) {
            logger.error(e) { "Failed to process swap conflict check response: $message" }
        }
    }
}
