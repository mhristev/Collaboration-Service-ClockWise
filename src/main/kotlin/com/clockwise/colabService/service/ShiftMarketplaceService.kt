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
import com.clockwise.colabService.listener.UsersByBusinessUnitResponseListener
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class ShiftMarketplaceService(
    private val exchangeShiftRepository: ExchangeShiftRepository,
    private val shiftRequestRepository: ShiftRequestRepository,
    private val kafkaProducerService: KafkaProducerService,
    private val usersByBusinessUnitResponseListener: UsersByBusinessUnitResponseListener,
    private val scheduleConflictService: ScheduleConflictService,
    private val isFirebaseEnabled: Boolean
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
                        .doOnSuccess { savedExchangeShift ->
                            logger.info { "Successfully posted shift to marketplace: ${savedExchangeShift.id}" }
                            // Send push notifications asynchronously after exchange shift is saved
                            if (isFirebaseEnabled) {
                                triggerExchangeShiftNotifications(savedExchangeShift)
                            } else {
                                logger.debug { "Firebase is disabled - skipping notifications for exchange shift ${savedExchangeShift.id}" }
                            }
                        }
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
                            
                            // Save the shift request first
                            shiftRequestRepository.save(shiftRequest)
                                .flatMap { savedRequest ->
                                    // Perform conflict check and update database in the reactive chain
                                    performConflictCheck(savedRequest, exchangeShift)
                                        .onErrorReturn(false) // If conflict check fails, treat as not executable
                                        .doOnNext { isExecutionPossible ->
                                            logger.info { "Conflict check completed for request ${savedRequest.id}: executionPossible=$isExecutionPossible" }
                                        }
                                        .doOnError { error ->
                                            logger.error(error) { "Conflict check failed for request ${savedRequest.id}, setting execution possibility to false" }
                                        }
                                        .flatMap { isExecutionPossible ->
                                            // Update the request with conflict check result in the reactive chain
                                            scheduleConflictService.updateShiftRequestConflictStatus(
                                                savedRequest.id!!, 
                                                isExecutionPossible
                                            ).thenReturn(savedRequest)
                                        }
                                }
                                .doOnSuccess { savedRequest ->
                                    logger.info { "Successfully submitted shift request: ${savedRequest.id}" }
                                    // Send notification to the exchange shift poster
                                    if (isFirebaseEnabled) {
                                        triggerShiftRequestNotifications(savedRequest, exchangeShift)
                                    } else {
                                        logger.debug { "Firebase is disabled - skipping shift request notifications for request ${savedRequest.id}" }
                                    }
                                }
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
            .doOnSuccess { (updatedExchangeShift, updatedRequest) ->
                logger.info { "Successfully accepted request $requestId" }
                // Send manager approval notifications
                if (isFirebaseEnabled) {
                    triggerManagerApprovalNotifications(updatedRequest, updatedExchangeShift)
                } else {
                    logger.debug { "Firebase is disabled - skipping manager approval notifications for request $requestId" }
                }
            }
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
            .doOnSuccess { (updatedExchangeShift, updatedRequest) ->
                logger.info { "Successfully updated exchange shift $exchangeShiftId to $status" }
                // Send approval notifications when approved
                if (status.uppercase() == "APPROVED" && isFirebaseEnabled) {
                    triggerApprovalNotifications(updatedRequest, updatedExchangeShift)
                } else if (status.uppercase() == "APPROVED") {
                    logger.debug { "Firebase is disabled - skipping approval notifications for exchange shift $exchangeShiftId" }
                }
            }
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
                                    Pair(
                                        exchangeShift.copy(
                                            status = ExchangeShiftStatus.APPROVED,
                                            updatedAt = OffsetDateTime.now()
                                        ),
                                        request.copy(
                                            status = RequestStatus.APPROVED_BY_MANAGER,
                                            updatedAt = OffsetDateTime.now()
                                        )
                                    )
                                )
                            )
                    }
            }
            .map { it.second } // Return only the ShiftRequest for the controller response
            .doOnSuccess { approvedRequest ->
                logger.info { "Successfully approved request $requestId" }
                // Send approval notifications
                if (isFirebaseEnabled) {
                    // We need to reconstruct the exchange shift from the database since we only have the request
                    exchangeShiftRepository.findById(approvedRequest.exchangeShiftId)
                        .subscribe { exchangeShift ->
                            triggerApprovalNotifications(approvedRequest, exchangeShift)
                        }
                } else {
                    logger.debug { "Firebase is disabled - skipping approval notifications for request $requestId" }
                }
            }
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
    
    /**
     * Triggers push notifications for a new exchange shift by requesting users from User Service
     */
    private fun triggerExchangeShiftNotifications(exchangeShift: ExchangeShift) {
        try {
            val correlationId = UUID.randomUUID().toString()
            
            // Register pending notification
            usersByBusinessUnitResponseListener.registerPendingExchangeShiftNotification(correlationId, exchangeShift)
            
            // Request users by business unit
            kafkaProducerService.requestUsersByBusinessUnitId(exchangeShift.businessUnitId, correlationId)
                .subscribe(
                    { 
                        logger.debug { "Successfully requested users for exchange shift notification: ${exchangeShift.id}" }
                    },
                    { error ->
                        logger.error("Failed to request users for exchange shift notification: ${error.message}", error)
                    }
                )
        } catch (e: Exception) {
            logger.error("Error triggering notifications for exchange shift ${exchangeShift.id}: ${e.message}", e)
        }
    }
    
    /**
     * Triggers push notifications for a new shift request by requesting users from User Service
     */
    private fun triggerShiftRequestNotifications(shiftRequest: ShiftRequest, exchangeShift: ExchangeShift) {
        try {
            val correlationId = UUID.randomUUID().toString()
            
            logger.info { "Triggering shift request notification for request ${shiftRequest.id} to poster ${exchangeShift.posterUserId}" }
            
            // Register pending notification
            usersByBusinessUnitResponseListener.registerPendingShiftRequestNotification(
                correlationId, 
                shiftRequest, 
                exchangeShift
            )
            
            // Request users by business unit (we need to find the poster in the business unit)
            kafkaProducerService.requestUsersByBusinessUnitId(exchangeShift.businessUnitId, correlationId)
                .subscribe(
                    { 
                        logger.debug { "Successfully requested users for shift request notification: ${shiftRequest.id}" }
                    },
                    { error ->
                        logger.error("Failed to request users for shift request notification: ${error.message}", error)
                    }
                )
        } catch (e: Exception) {
            logger.error("Error triggering notifications for shift request ${shiftRequest.id}: ${e.message}", e)
        }
    }
    
    /**
     * Triggers push notifications for manager approval by requesting users from User Service
     */
    private fun triggerManagerApprovalNotifications(shiftRequest: ShiftRequest, exchangeShift: ExchangeShift) {
        try {
            val correlationId = UUID.randomUUID().toString()
            
            logger.info { "Triggering manager approval notification for request ${shiftRequest.id} in business unit ${exchangeShift.businessUnitId}" }
            
            // Register pending notification
            usersByBusinessUnitResponseListener.registerPendingManagerApprovalNotification(
                correlationId, 
                shiftRequest, 
                exchangeShift
            )
            
            // Request users by business unit (we need to find managers in the business unit)
            kafkaProducerService.requestUsersByBusinessUnitId(exchangeShift.businessUnitId, correlationId)
                .subscribe(
                    { 
                        logger.debug { "Successfully requested users for manager approval notification: ${shiftRequest.id}" }
                    },
                    { error ->
                        logger.error("Failed to request users for manager approval notification: ${error.message}", error)
                    }
                )
        } catch (e: Exception) {
            logger.error("Error triggering notifications for manager approval ${shiftRequest.id}: ${e.message}", e)
        }
    }
    
    /**
     * Triggers push notifications for approval by requesting users from User Service
     */
    private fun triggerApprovalNotifications(shiftRequest: ShiftRequest, exchangeShift: ExchangeShift) {
        try {
            val correlationId = UUID.randomUUID().toString()
            
            logger.info { "Triggering approval notifications for request ${shiftRequest.id} in business unit ${exchangeShift.businessUnitId}" }
            
            // Register pending notification
            usersByBusinessUnitResponseListener.registerPendingApprovalNotification(
                correlationId, 
                shiftRequest, 
                exchangeShift
            )
            
            // Request users by business unit (we need to find both poster and requester in the business unit)
            kafkaProducerService.requestUsersByBusinessUnitId(exchangeShift.businessUnitId, correlationId)
                .subscribe(
                    { 
                        logger.debug { "Successfully requested users for approval notification: ${shiftRequest.id}" }
                    },
                    { error ->
                        logger.error("Failed to request users for approval notification: ${error.message}", error)
                    }
                )
        } catch (e: Exception) {
            logger.error("Error triggering approval notifications for request ${shiftRequest.id}: ${e.message}", e)
        }
    }
    
    /**
     * Performs conflict checking for a shift request based on the request type
     */
    private fun performConflictCheck(shiftRequest: ShiftRequest, exchangeShift: ExchangeShift): Mono<Boolean> {
        return when (shiftRequest.requestType) {
            RequestType.TAKE_SHIFT -> {
                logger.info { "Performing TAKE_SHIFT conflict check for request ${shiftRequest.id}" }
                if (exchangeShift.shiftStartTime == null || exchangeShift.shiftEndTime == null) {
                    logger.warn { "Exchange shift ${exchangeShift.id} missing time information, cannot perform conflict check" }
                    Mono.just(false) // Cannot execute if time info is missing
                } else {
                    scheduleConflictService.checkTakeShiftConflicts(
                        requesterUserId = shiftRequest.requesterUserId,
                        shiftStartTime = exchangeShift.shiftStartTime,
                        shiftEndTime = exchangeShift.shiftEndTime
                    )
                }
            }
            RequestType.SWAP_SHIFT -> {
                logger.info { "Performing SWAP_SHIFT conflict check for request ${shiftRequest.id}" }
                if (shiftRequest.swapShiftId == null) {
                    logger.error { "SWAP_SHIFT request ${shiftRequest.id} missing swapShiftId" }
                    Mono.just(false) // Cannot execute swap without swap shift ID
                } else {
                    scheduleConflictService.checkSwapShiftConflicts(
                        posterUserId = exchangeShift.posterUserId,
                        requesterUserId = shiftRequest.requesterUserId,
                        originalShiftId = exchangeShift.planningServiceShiftId,
                        swapShiftId = shiftRequest.swapShiftId
                    )
                }
            }
        }
    }
    
    /**
     * Trigger a conflict check for an existing shift request
     */
    @Transactional
    fun recheckConflicts(requestId: String): Mono<ShiftRequest> {
        logger.info { "Rechecking conflicts for request $requestId" }
        
        return shiftRequestRepository.findById(requestId)
            .switchIfEmpty(Mono.error(IllegalArgumentException("Request not found")))
            .doOnNext { shiftRequest ->
                logger.info { "Found shift request: requestType=${shiftRequest.requestType}, exchangeShiftId=${shiftRequest.exchangeShiftId}" }
            }
            .flatMap { shiftRequest ->
                exchangeShiftRepository.findById(shiftRequest.exchangeShiftId)
                    .switchIfEmpty(Mono.error(IllegalArgumentException("Exchange shift not found")))
                    .doOnNext { exchangeShift ->
                        logger.info { "Found exchange shift: planningServiceShiftId=${exchangeShift.planningServiceShiftId}, shiftStartTime=${exchangeShift.shiftStartTime}, shiftEndTime=${exchangeShift.shiftEndTime}" }
                    }
                    .flatMap { exchangeShift ->
                        // Perform conflict check
                        performConflictCheck(shiftRequest, exchangeShift)
                            .doOnNext { result ->
                                logger.info { "Conflict check result for request $requestId: isExecutionPossible=$result" }
                            }
                            .onErrorResume { error ->
                                logger.error(error) { "Conflict check failed for request $requestId, defaulting to false" }
                                Mono.just(false) // If conflict check fails, treat as not executable
                            }
                            .flatMap { isExecutionPossible ->
                                // Update the request with new conflict check result
                                logger.info { "Updating shift request $requestId with isExecutionPossible=$isExecutionPossible" }
                                scheduleConflictService.updateShiftRequestConflictStatus(
                                    requestId, 
                                    isExecutionPossible
                                ).doOnSuccess { 
                                    logger.info { "Successfully updated shift request $requestId conflict status" }
                                }
                                .doOnError { error ->
                                    logger.error(error) { "Failed to update shift request $requestId conflict status" }
                                }
                                .then(
                                    // Return updated request
                                    shiftRequestRepository.findById(requestId)
                                        .map { updatedRequest ->
                                            updatedRequest.copy(isExecutionPossible = isExecutionPossible)
                                        }
                                )
                            }
                    }
            }
            .doOnSuccess { updatedRequest ->
                logger.info { "Conflict recheck completed for request $requestId: executionPossible=${updatedRequest.isExecutionPossible}" }
            }
            .doOnError { error ->
                logger.error(error) { "Conflict recheck failed for request $requestId" }
            }
    }
}