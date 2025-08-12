package com.clockwise.colabService.repository

import com.clockwise.colabService.domain.RequestStatus
import com.clockwise.colabService.domain.ShiftRequest
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface ShiftRequestRepository : R2dbcRepository<ShiftRequest, String> {
    
    @Query("SELECT * FROM shift_requests WHERE exchange_shift_id = :exchangeShiftId ORDER BY created_at ASC")
    fun findByExchangeShiftIdOrderByCreatedAtAsc(exchangeShiftId: String): Flux<ShiftRequest>
    
    @Query("SELECT * FROM shift_requests WHERE exchange_shift_id = :exchangeShiftId AND status = :status ORDER BY created_at ASC")
    fun findByExchangeShiftIdAndStatusOrderByCreatedAtAsc(
        exchangeShiftId: String,
        status: RequestStatus
    ): Flux<ShiftRequest>
    
    @Query("SELECT * FROM shift_requests WHERE requester_user_id = :requesterUserId ORDER BY created_at DESC")
    fun findByRequesterUserIdOrderByCreatedAtDesc(requesterUserId: String): Flux<ShiftRequest>
    
    @Query("SELECT * FROM shift_requests WHERE requester_user_id = :requesterUserId AND status IN (:statuses) ORDER BY created_at DESC")
    fun findByRequesterUserIdAndStatusInOrderByCreatedAtDesc(
        requesterUserId: String,
        statuses: List<RequestStatus>
    ): Flux<ShiftRequest>
    
    @Query("""
        SELECT sr.* FROM shift_requests sr 
        INNER JOIN exchange_shifts es ON sr.exchange_shift_id = es.id 
        WHERE es.poster_user_id = :posterUserId 
        ORDER BY sr.created_at DESC
    """)
    fun findRequestsForPosterShifts(posterUserId: String): Flux<ShiftRequest>
    
    @Query("""
        SELECT sr.* FROM shift_requests sr 
        INNER JOIN exchange_shifts es ON sr.exchange_shift_id = es.id 
        WHERE es.poster_user_id = :posterUserId AND sr.status = :status
        ORDER BY sr.created_at ASC
    """)
    fun findRequestsForPosterShiftsByStatus(
        posterUserId: String,
        status: RequestStatus
    ): Flux<ShiftRequest>
    
    @Query("""
        SELECT sr.* FROM shift_requests sr 
        INNER JOIN exchange_shifts es ON sr.exchange_shift_id = es.id 
        WHERE es.business_unit_id = :businessUnitId AND sr.status = 'ACCEPTED_BY_POSTER'
        ORDER BY sr.created_at ASC
    """)
    fun findPendingManagerApprovalsByBusinessUnitId(businessUnitId: String): Flux<ShiftRequest>
    
    @Query("SELECT * FROM shift_requests WHERE id = :id AND requester_user_id = :requesterUserId")
    fun findByIdAndRequesterUserId(id: String, requesterUserId: String): Mono<ShiftRequest>
    
    @Query("SELECT * FROM shift_requests WHERE exchange_shift_id = :exchangeShiftId AND requester_user_id = :requesterUserId")
    fun findByExchangeShiftIdAndRequesterUserId(
        exchangeShiftId: String,
        requesterUserId: String
    ): Mono<ShiftRequest>
    
    @Query("UPDATE shift_requests SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
    fun updateStatus(id: String, status: RequestStatus): Mono<Void>
    
    @Query("UPDATE shift_requests SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE exchange_shift_id = :exchangeShiftId AND id != :excludeRequestId")
    fun declineOtherRequests(
        exchangeShiftId: String,
        excludeRequestId: String,
        status: RequestStatus
    ): Mono<Void>
    
    @Query("SELECT COUNT(*) FROM shift_requests WHERE exchange_shift_id = :exchangeShiftId AND requester_user_id = :requesterUserId")
    fun countByExchangeShiftIdAndRequesterUserId(
        exchangeShiftId: String,
        requesterUserId: String
    ): Mono<Long>
}