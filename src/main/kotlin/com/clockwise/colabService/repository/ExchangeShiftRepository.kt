package com.clockwise.colabService.repository

import com.clockwise.colabService.domain.ExchangeShift
import com.clockwise.colabService.domain.ExchangeShiftStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface ExchangeShiftRepository : R2dbcRepository<ExchangeShift, String> {
    
    @Query("SELECT * FROM exchange_shifts WHERE business_unit_id = :businessUnitId AND status = :status ORDER BY created_at DESC")
    fun findByBusinessUnitIdAndStatusOrderByCreatedAtDesc(
        businessUnitId: String,
        status: ExchangeShiftStatus
    ): Flux<ExchangeShift>
    
    @Query("SELECT * FROM exchange_shifts WHERE business_unit_id = :businessUnitId AND status = 'OPEN' ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    fun findOpenShiftsByBusinessUnitId(
        businessUnitId: String,
        limit: Long,
        offset: Long
    ): Flux<ExchangeShift>
    
    @Query("SELECT COUNT(*) FROM exchange_shifts WHERE business_unit_id = :businessUnitId AND status = 'OPEN'")
    fun countOpenShiftsByBusinessUnitId(businessUnitId: String): Mono<Long>
    
    @Query("SELECT * FROM exchange_shifts WHERE poster_user_id = :posterUserId ORDER BY created_at DESC")
    fun findByPosterUserIdOrderByCreatedAtDesc(posterUserId: String): Flux<ExchangeShift>
    
    @Query("SELECT * FROM exchange_shifts WHERE poster_user_id = :posterUserId AND status IN (:statuses) ORDER BY created_at DESC")
    fun findByPosterUserIdAndStatusInOrderByCreatedAtDesc(
        posterUserId: String,
        statuses: List<ExchangeShiftStatus>
    ): Flux<ExchangeShift>
    
    @Query("SELECT * FROM exchange_shifts WHERE status = 'AWAITING_MANAGER_APPROVAL' AND business_unit_id = :businessUnitId ORDER BY created_at ASC")
    fun findPendingManagerApprovalsByBusinessUnitId(businessUnitId: String): Flux<ExchangeShift>
    
    @Query("SELECT * FROM exchange_shifts WHERE id = :id AND poster_user_id = :posterUserId")
    fun findByIdAndPosterUserId(id: String, posterUserId: String): Mono<ExchangeShift>
    
    @Query("SELECT * FROM exchange_shifts WHERE planning_service_shift_id = :shiftId AND poster_user_id = :posterUserId")
    fun findByPlanningServiceShiftIdAndPosterUserId(
        shiftId: String,
        posterUserId: String
    ): Mono<ExchangeShift>
    
    @Query("UPDATE exchange_shifts SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
    fun updateStatus(id: String, status: ExchangeShiftStatus): Mono<Void>
    
    @Query("UPDATE exchange_shifts SET status = :status, accepted_request_id = :acceptedRequestId, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
    fun updateStatusAndAcceptedRequest(
        id: String,
        status: ExchangeShiftStatus,
        acceptedRequestId: String?
    ): Mono<Void>
}