package com.clockwise.colabService.listener

import com.clockwise.colabService.dto.ShiftExchangeConfirmationDto
import com.clockwise.colabService.service.ShiftExchangeConfirmationService
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Listener for shift exchange confirmation events from Planning Service
 * Handles confirmation that shift exchanges have been successfully processed
 */
@Component
class ShiftExchangeConfirmationListener(
    private val shiftExchangeConfirmationService: ShiftExchangeConfirmationService,
    private val objectMapper: ObjectMapper
) {

    @KafkaListener(
        topics = ["\${kafka.topic.shift-exchange-confirmations}"],
        groupId = "\${spring.kafka.consumer.group-id}",
        containerFactory = "stringKafkaListenerContainerFactory"
    )
    fun handleShiftExchangeConfirmation(message: String, ack: Acknowledgment) {
        try {
            logger.info { "Received shift exchange confirmation message: $message" }
            
            val confirmation = objectMapper.readValue(message, ShiftExchangeConfirmationDto::class.java)
            logger.info { "Processing shift exchange confirmation - Status: ${confirmation.status}, Request: ${confirmation.requestId}, Original Shift: ${confirmation.originalShiftId}" }
            
            // Process the confirmation
            shiftExchangeConfirmationService.handleConfirmation(confirmation)
            
            ack.acknowledge()
            logger.info { "Successfully processed shift exchange confirmation for request: ${confirmation.requestId}" }
            
        } catch (e: Exception) {
            logger.error(e) { "Error processing shift exchange confirmation message: $message" }
            throw e
        }
    }
}
