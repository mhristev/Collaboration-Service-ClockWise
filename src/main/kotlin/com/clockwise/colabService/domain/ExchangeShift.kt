package com.clockwise.colabService.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime

@Table("exchange_shifts")
data class ExchangeShift(
    @Id
    val id: String? = null,
    
    @Column("planning_service_shift_id")
    val planningServiceShiftId: String,
    
    @Column("poster_user_id")
    val posterUserId: String,
    
    @Column("business_unit_id")
    val businessUnitId: String,
    
    val status: ExchangeShiftStatus = ExchangeShiftStatus.OPEN,
    
    @Column("accepted_request_id")
    val acceptedRequestId: String? = null,
    
    @Column("shift_position")
    val shiftPosition: String? = null,
    
    @Column("shift_start_time")
    val shiftStartTime: OffsetDateTime? = null,
    
    @Column("shift_end_time")
    val shiftEndTime: OffsetDateTime? = null,
    
    @Column("user_first_name")
    val userFirstName: String? = null,
    
    @Column("user_last_name")
    val userLastName: String? = null,
    
    @Column("created_at")
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    
    @Column("updated_at")
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
) {
    fun canAcceptRequests(): Boolean {
        return status == ExchangeShiftStatus.OPEN
    }
    
    fun canBeModifiedByPoster(): Boolean {
        return status in listOf(ExchangeShiftStatus.OPEN, ExchangeShiftStatus.PENDING_SELECTION)
    }
    
    fun isPendingManagerApproval(): Boolean {
        return status == ExchangeShiftStatus.AWAITING_MANAGER_APPROVAL
    }
    
    fun isCompleted(): Boolean {
        return status in listOf(ExchangeShiftStatus.APPROVED, ExchangeShiftStatus.REJECTED, ExchangeShiftStatus.CANCELLED)
    }
}

enum class ExchangeShiftStatus {
    OPEN,
    PENDING_SELECTION,
    AWAITING_MANAGER_APPROVAL,
    APPROVED,
    REJECTED,
    CANCELLED,
    COMPLETED
}