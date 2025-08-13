package com.clockwise.colabService.service

import com.clockwise.colabService.domain.ExchangeShift
import com.clockwise.colabService.domain.ExchangeShiftStatus
import com.clockwise.colabService.domain.RequestStatus
import com.clockwise.colabService.domain.RequestType
import com.clockwise.colabService.domain.ShiftRequest
import com.clockwise.colabService.dto.CreateExchangeShiftRequest
import com.clockwise.colabService.dto.CreateShiftRequestRequest
import com.clockwise.colabService.dto.ShiftExchangeEventDto
import com.clockwise.colabService.repository.ExchangeShiftRepository
import com.clockwise.colabService.repository.ShiftRequestRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.OffsetDateTime

private val logger = KotlinLogging.logger {}

@Service
class ShiftMarketplaceService(
    private val exchangeShiftRepository: ExchangeShiftRepository,
    private val shiftRequestRepository: ShiftRequestRepository,
    private val kafkaProducerService: KafkaProducerService
) {
    
    @Transactional
    fun postShiftToMarketplace(
        request: CreateExchangeShiftRequest,
        posterUserId: String
    ): Mono<ExchangeShift> {
        logger.info { "User $posterUserId posting shift ${request.planningServiceShiftId} to marketplace" }
        
        // Check if shift is already posted
        return exchangeShiftRepository
            .findByPlanningServiceShiftIdAndPosterUserId(request.planningServiceShiftId, posterUserId)
            .doOnNext { 
                logger.warn { "Shift ${request.planningServiceShiftId} already posted by user $posterUserId" }
            }
            .switchIfEmpty(
                Mono.defer {
                    val exchangeShift = ExchangeShift(
                        planningServiceShiftId = request.planningServiceShiftId,
                        posterUserId = posterUserId,
                        businessUnitId = request.businessUnitId,
                        shiftPosition = request.shiftPosition,
                        shiftStartTime = request.shiftStartTime,
                        shiftEndTime = request.shiftEndTime,
                        userFirstName = request.userFirstName,
                        userLastName = request.userLastName
                    )
                    exchangeShiftRepository.save(exchangeShift)
                        .doOnSuccess { logger.info { "Successfully posted shift to marketplace: ${it.id}" } }
                }
            )
    }
    
    fun getAvailableShifts(businessUnitId: String, page: Int, size: Int): Flux<ExchangeShift> {
        logger.debug { "Getting available shifts for business unit $businessUnitId, page $page, size $size" }
        val offset = (page * size).toLong()
        return exchangeShiftRepository.findOpenShiftsByBusinessUnitId(businessUnitId, size.toLong(), offset)
    }
    
    fun countAvailableShifts(businessUnitId: String): Mono<Long> {
        return exchangeShiftRepository.countOpenShiftsByBusinessUnitId(businessUnitId)
    }
    
    fun getUserPostedShifts(posterUserId: String): Flux<ExchangeShift> {
        return exchangeShiftRepository.findByPosterUserIdOrderByCreatedAtDesc(posterUserId)
    }
    
    @Transactional
    fun submitShiftRequest(
        exchangeShiftId: String,
        request: CreateShiftRequestRequest,
        requesterUserId: String
    ): Mono<ShiftRequest> {
        logger.info { "User $requesterUserId submitting ${request.requestType} request for exchange shift $exchangeShiftId" }
        
        return exchangeShiftRepository.findById(exchangeShiftId)
            .switchIfEmpty(Mono.error(IllegalArgumentException("Exchange shift not found")))
            .filter { it.canAcceptRequests() }
            .switchIfEmpty(Mono.error(IllegalStateException("Exchange shift is not accepting requests")))
            .filter { it.posterUserId != requesterUserId }
            .switchIfEmpty(Mono.error(IllegalArgumentException("Cannot request your own shift")))
            .flatMap { exchangeShift ->
                // Check if user already has a request for this shift
                shiftRequestRepository.countByExchangeShiftIdAndRequesterUserId(exchangeShiftId, requesterUserId)
                    .flatMap { count ->
                        if (count > 0) {
                            Mono.error<ShiftRequest>(IllegalStateException("Already have a request for this shift"))
                        } else {
                            val shiftRequest = ShiftRequest(
                                exchangeShiftId = exchangeShiftId,
                                requesterUserId = requesterUserId,
                                requestType = request.requestType,
                                swapShiftId = request.swapShiftId,
                                swapShiftPosition = request.swapShiftPosition,
                                swapShiftStartTime = request.swapShiftStartTime,
                                swapShiftEndTime = request.swapShiftEndTime,
                                requesterUserFirstName = request.requesterUserFirstName,
                                requesterUserLastName = request.requesterUserLastName
                            )
                            shiftRequestRepository.save(shiftRequest)
                                .doOnSuccess { logger.info { "Successfully submitted shift request: ${it.id}" } }
                        }
                    }
            }
    }
    
    fun getRequestsForExchangeShift(exchangeShiftId: String, posterUserId: String): Flux<ShiftRequest> {
        return exchangeShiftRepository.findByIdAndPosterUserId(exchangeShiftId, posterUserId)
            .switchIfEmpty(Mono.error(IllegalArgumentException("Exchange shift not found or not owned by user")))
            .flatMapMany { 
                shiftRequestRepository.findByExchangeShiftIdAndStatusOrderByCreatedAtAsc(
                    exchangeShiftId, 
                    RequestStatus.PENDING
                )
            }
    }
    
    fun getUserRequests(requesterUserId: String): Flux<ShiftRequest> {
        return shiftRequestRepository.findByRequesterUserIdOrderByCreatedAtDesc(requesterUserId)
    }
    
    
    @Transactional
    fun acceptRequest(
        exchangeShiftId: String,
        requestId: String,
        posterUserId: String
    ): Mono<Pair<ExchangeShift, ShiftRequest>> {
        logger.info { "User $posterUserId accepting request $requestId for exchange shift $exchangeShiftId" }
        
        return exchangeShiftRepository.findByIdAndPosterUserId(exchangeShiftId, posterUserId)
            .switchIfEmpty(Mono.error(IllegalArgumentException("Exchange shift not found or not owned by user")))
            .filter { it.canAcceptRequests() }
            .switchIfEmpty(Mono.error(IllegalStateException("Exchange shift cannot accept requests")))
            .flatMap { exchangeShift ->
                shiftRequestRepository.findById(requestId)
                    .switchIfEmpty(Mono.error(IllegalArgumentException("Request not found")))
                    .filter { it.exchangeShiftId == exchangeShiftId && it.isPending() }
                    .switchIfEmpty(Mono.error(IllegalStateException("Request is not valid for acceptance")))
                    .flatMap { request ->
                        // Update exchange shift status and accepted request
                        exchangeShiftRepository.updateStatusAndAcceptedRequest(
                            exchangeShiftId,
                            ExchangeShiftStatus.AWAITING_MANAGER_APPROVAL,
                            requestId
                        ).then(
                            // Update request status
                            shiftRequestRepository.updateStatus(requestId, RequestStatus.ACCEPTED_BY_POSTER)
                        ).then(
                            // Decline all other requests for this exchange shift
                            shiftRequestRepository.declineOtherRequests(
                                exchangeShiftId,
                                requestId,
                                RequestStatus.DECLINED_BY_POSTER
                            )
                        ).then(
                            Mono.just(
                                Pair(
                                    exchangeShift.copy(
                                        status = ExchangeShiftStatus.AWAITING_MANAGER_APPROVAL,
                                        acceptedRequestId = requestId,
                                        updatedAt = OffsetDateTime.now()
                                    ),
                                    request.copy(
                                        status = RequestStatus.ACCEPTED_BY_POSTER,
                                        updatedAt = OffsetDateTime.now()
                                    )
                                )
                            )
                        )
                    }
            }
            .doOnSuccess { logger.info { "Successfully accepted request $requestId" } }
    }
    
    fun getPendingManagerApprovals(businessUnitId: String): Flux<ShiftRequest> {
        return shiftRequestRepository.findPendingManagerApprovalsByBusinessUnitId(businessUnitId)
    }
    
    fun getAwaitingManagerApprovalExchanges(businessUnitId: String): Flux<Pair<ExchangeShift, ShiftRequest>> {
        return exchangeShiftRepository.findPendingManagerApprovalsByBusinessUnitId(businessUnitId)
            .flatMap { exchangeShift ->
                if (exchangeShift.acceptedRequestId != null) {
                    shiftRequestRepository.findById(exchangeShift.acceptedRequestId)
                        .map { request -> Pair(exchangeShift, request) }
                } else {
                    Mono.empty()
                }
            }
    }
    
    @Transactional
    fun updateExchangeShiftStatus(
        exchangeShiftId: String, 
        status: String,
        businessUnitId: String
    ): Mono<Pair<ExchangeShift, ShiftRequest>> {
        logger.info { "Updating exchange shift $exchangeShiftId status to $status" }
        
        val newExchangeStatus = when (status.uppercase()) {
            "APPROVED" -> ExchangeShiftStatus.APPROVED
            "REJECTED" -> ExchangeShiftStatus.REJECTED
            else -> throw IllegalArgumentException("Invalid status: $status. Must be APPROVED or REJECTED")
        }
        
        val newRequestStatus = when (status.uppercase()) {
            "APPROVED" -> RequestStatus.APPROVED_BY_MANAGER
            "REJECTED" -> RequestStatus.REJECTED_BY_MANAGER
            else -> throw IllegalArgumentException("Invalid status: $status. Must be APPROVED or REJECTED")
        }
        
        return exchangeShiftRepository.findById(exchangeShiftId)
            .switchIfEmpty(Mono.error(IllegalArgumentException("Exchange shift not found")))
            .filter { it.businessUnitId == businessUnitId }
            .switchIfEmpty(Mono.error(IllegalArgumentException("Exchange shift not found in business unit")))
            .filter { it.status == ExchangeShiftStatus.AWAITING_MANAGER_APPROVAL }
            .switchIfEmpty(Mono.error(IllegalStateException("Exchange shift is not awaiting manager approval")))
            .filter { it.acceptedRequestId != null }
            .switchIfEmpty(Mono.error(IllegalStateException("Exchange shift has no accepted request")))
            .flatMap { exchangeShift ->
                shiftRequestRepository.findById(exchangeShift.acceptedRequestId!!)
                    .switchIfEmpty(Mono.error(IllegalArgumentException("Accepted request not found")))
                    .flatMap { request ->
                        // Update exchange shift status
                        exchangeShiftRepository.updateStatus(exchangeShiftId, newExchangeStatus)
                            .then(
                                // Update request status
                                shiftRequestRepository.updateStatus(request.id!!, newRequestStatus)
                            )
                            .then(
                                // Handle different status outcomes
                                if (status.uppercase() == "APPROVED") {
                                    // Send approval event to Planning Service to execute the shift change
                                    sendApprovalEventToPlanning(request, exchangeShift, "APPROVED")
                                } else {
                                    // If rejected, return exchange shift to marketplace and notify Planning Service
                                    exchangeShiftRepository.updateStatusAndAcceptedRequest(
                                        exchangeShiftId,
                                        ExchangeShiftStatus.OPEN,
                                        null
                                    ).then(
                                        // Send rejection event (Planning Service may need to know for logging/auditing)
                                        sendApprovalEventToPlanning(request, exchangeShift, "REJECTED")
                                    )
                                }
                            )
                            .then(
                                Mono.just(
                                    Pair(
                                        exchangeShift.copy(
                                            status = newExchangeStatus,
                                            updatedAt = OffsetDateTime.now()
                                        ),
                                        request.copy(
                                            status = newRequestStatus,
                                            updatedAt = OffsetDateTime.now()
                                        )
                                    )
                                )
                            )
                    }
            }
            .doOnSuccess { logger.info { "Successfully updated exchange shift $exchangeShiftId to $status" } }
    }
    
    @Transactional
    fun approveRequest(requestId: String): Mono<ShiftRequest> {
        logger.info { "Manager approving request $requestId" }
        
        return shiftRequestRepository.findById(requestId)
            .switchIfEmpty(Mono.error(IllegalArgumentException("Request not found")))
            .filter { it.isAcceptedByPoster() }
            .switchIfEmpty(Mono.error(IllegalStateException("Request is not awaiting manager approval")))
            .flatMap { request ->
                exchangeShiftRepository.findById(request.exchangeShiftId)
                    .flatMap { exchangeShift ->
                        // Update request status
                        shiftRequestRepository.updateStatus(requestId, RequestStatus.APPROVED_BY_MANAGER)
                            .then(
                                // Update exchange shift status
                                exchangeShiftRepository.updateStatus(request.exchangeShiftId, ExchangeShiftStatus.APPROVED)
                            )
                            .then(
                                // Send Kafka event to Planning Service
                                sendApprovalEventToPlanning(request, exchangeShift)
                            )
                            .then(
                                Mono.just(
                                    request.copy(
                                        status = RequestStatus.APPROVED_BY_MANAGER,
                                        updatedAt = OffsetDateTime.now()
                                    )
                                )
                            )
                    }
            }
            .doOnSuccess { logger.info { "Successfully approved request $requestId" } }
    }
    
    @Transactional
    fun rejectRequest(requestId: String): Mono<ShiftRequest> {
        logger.info { "Manager rejecting request $requestId" }
        
        return shiftRequestRepository.findById(requestId)
            .switchIfEmpty(Mono.error(IllegalArgumentException("Request not found")))
            .filter { it.isAcceptedByPoster() }
            .switchIfEmpty(Mono.error(IllegalStateException("Request is not awaiting manager approval")))
            .flatMap { request ->
                // Update request status
                shiftRequestRepository.updateStatus(requestId, RequestStatus.REJECTED_BY_MANAGER)
                    .then(
                        // Put exchange shift back on marketplace
                        exchangeShiftRepository.updateStatusAndAcceptedRequest(
                            request.exchangeShiftId,
                            ExchangeShiftStatus.OPEN,
                            null
                        )
                    )
                    .then(
                        Mono.just(
                            request.copy(
                                status = RequestStatus.REJECTED_BY_MANAGER,
                                updatedAt = OffsetDateTime.now()
                            )
                        )
                    )
            }
            .doOnSuccess { logger.info { "Successfully rejected request $requestId, shift returned to marketplace" } }
    }
    
    @Transactional
    fun cancelExchangeShift(exchangeShiftId: String, posterUserId: String): Mono<ExchangeShift> {
        logger.info { "User $posterUserId cancelling exchange shift $exchangeShiftId" }
        
        return exchangeShiftRepository.findByIdAndPosterUserId(exchangeShiftId, posterUserId)
            .switchIfEmpty(Mono.error(IllegalArgumentException("Exchange shift not found or not owned by user")))
            .filter { it.canBeModifiedByPoster() }
            .switchIfEmpty(Mono.error(IllegalStateException("Exchange shift cannot be cancelled in current state")))
            .flatMap { exchangeShift ->
                exchangeShiftRepository.updateStatus(exchangeShiftId, ExchangeShiftStatus.CANCELLED)
                    .then(
                        Mono.just(
                            exchangeShift.copy(
                                status = ExchangeShiftStatus.CANCELLED,
                                updatedAt = OffsetDateTime.now()
                            )
                        )
                    )
            }
            .doOnSuccess { logger.info { "Successfully cancelled exchange shift $exchangeShiftId" } }
    }
    
    private fun sendApprovalEventToPlanning(request: ShiftRequest, exchangeShift: ExchangeShift, status: String = "APPROVED"): Mono<Void> {
        val event = ShiftExchangeEventDto(
            requestId = request.id!!,
            exchangeShiftId = exchangeShift.id!!,
            originalShiftId = exchangeShift.planningServiceShiftId,
            posterUserId = exchangeShift.posterUserId,
            requesterUserId = request.requesterUserId,
            requestType = request.requestType,
            swapShiftId = request.swapShiftId,
            businessUnitId = exchangeShift.businessUnitId,
            status = status
        )
        
        logger.info { "Sending ${status.lowercase()} event for ${request.requestType} request ${request.id} to Planning Service" }
        
        return kafkaProducerService.sendShiftExchangeApprovalEvent(event)
            .doOnSuccess { 
                logger.info { 
                    when (request.requestType) {
                        RequestType.SWAP_SHIFT -> "Sent SWAP_SHIFT $status event: original=${exchangeShift.planningServiceShiftId}, swap=${request.swapShiftId}"
                        RequestType.TAKE_SHIFT -> "Sent TAKE_SHIFT $status event: shift=${exchangeShift.planningServiceShiftId}, new_user=${request.requesterUserId}"
                    }
                }
            }
            .doOnError { error -> logger.error(error) { "Failed to send $status event to Planning Service" } }
            .onErrorResume { 
                // Log error but don't fail the transaction - the status change is still valid
                logger.warn { "Continuing with $status despite Kafka error" }
                Mono.empty()
            }
    }
}