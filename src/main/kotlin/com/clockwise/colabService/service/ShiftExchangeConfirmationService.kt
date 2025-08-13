package com.clockwise.colabService.service

import com.clockwise.colabService.domain.ExchangeShiftStatus
import com.clockwise.colabService.domain.RequestStatus
import com.clockwise.colabService.dto.ShiftExchangeConfirmationDto
import com.clockwise.colabService.repository.ExchangeShiftRepository
import com.clockwise.colabService.repository.ShiftRequestRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Mono
import java.time.OffsetDateTime

private val logger = KotlinLogging.logger {}

@Service
class ShiftExchangeConfirmationService(
    private val exchangeShiftRepository: ExchangeShiftRepository,
    private val shiftRequestRepository: ShiftRequestRepository
) {

    @Transactional
    fun handleConfirmation(confirmation: ShiftExchangeConfirmationDto): Mono<Void> {
        logger.info { "Handling shift exchange confirmation for request ${confirmation.requestId} with status ${confirmation.status}" }
        
        return when (confirmation.status.uppercase()) {
            "SUCCESS" -> handleSuccessConfirmation(confirmation)
            "FAILED" -> handleFailureConfirmation(confirmation)
            else -> {
                logger.warn { "Unknown confirmation status: ${confirmation.status} for request ${confirmation.requestId}" }
                Mono.empty()
            }
        }
    }

    private fun handleSuccessConfirmation(confirmation: ShiftExchangeConfirmationDto): Mono<Void> {
        logger.info { "Processing successful shift exchange confirmation for request ${confirmation.requestId}" }
        
        return shiftRequestRepository.findById(confirmation.requestId)
            .switchIfEmpty(Mono.error(IllegalArgumentException("Request not found: ${confirmation.requestId}")))
            .flatMap { request ->
                // Update request status to completed
                shiftRequestRepository.updateStatus(confirmation.requestId, RequestStatus.COMPLETED)
                    .then(
                        // Update exchange shift status to completed
                        exchangeShiftRepository.updateStatus(confirmation.exchangeShiftId, ExchangeShiftStatus.COMPLETED)
                    )
            }
            .doOnSuccess { 
                logger.info { "Successfully marked request ${confirmation.requestId} and exchange shift ${confirmation.exchangeShiftId} as completed" }
            }
            .doOnError { error ->
                logger.error(error) { "Failed to process success confirmation for request ${confirmation.requestId}" }
            }
            .then()
    }

    private fun handleFailureConfirmation(confirmation: ShiftExchangeConfirmationDto): Mono<Void> {
        logger.info { "Processing failed shift exchange confirmation for request ${confirmation.requestId}. Reason: ${confirmation.message}" }
        
        return shiftRequestRepository.findById(confirmation.requestId)
            .switchIfEmpty(Mono.error(IllegalArgumentException("Request not found: ${confirmation.requestId}")))
            .flatMap { request ->
                // Update request status to failed
                shiftRequestRepository.updateStatus(confirmation.requestId, RequestStatus.PROCESSING_FAILED)
                    .then(
                        // Return exchange shift to marketplace for re-posting
                        exchangeShiftRepository.updateStatusAndAcceptedRequest(
                            confirmation.exchangeShiftId,
                            ExchangeShiftStatus.OPEN,
                            null
                        )
                    )
            }
            .doOnSuccess { 
                logger.info { "Successfully handled failure for request ${confirmation.requestId}, returned exchange shift ${confirmation.exchangeShiftId} to marketplace" }
            }
            .doOnError { error ->
                logger.error(error) { "Failed to process failure confirmation for request ${confirmation.requestId}" }
            }
            .then()
    }
}
