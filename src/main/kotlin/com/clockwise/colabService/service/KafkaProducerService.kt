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
    
    fun sendShiftExchangeApprovalEvent(event: ShiftExchangeEventDto): Mono<Void> {
        return Mono.fromCallable {
            val eventJson = objectMapper.writeValueAsString(event)
            logger.info { "Sending shift exchange approval event to topic $shiftExchangeTopic: $eventJson" }
            
            kafkaTemplate.send(shiftExchangeTopic, event.requestId, eventJson)
        }
        .doOnSuccess { 
            logger.info { "Successfully sent shift exchange approval event for request ${event.requestId}" }
        }
        .doOnError { error ->
            logger.error(error) { "Failed to send shift exchange approval event for request ${event.requestId}" }
        }
        .then()
    }
}