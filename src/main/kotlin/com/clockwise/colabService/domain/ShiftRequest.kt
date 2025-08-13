package com.clockwise.colabService.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime

@Table("shift_requests")
data class ShiftRequest(
    @Id
    val id: String? = null,
    
    @Column("exchange_shift_id")
    val exchangeShiftId: String,
    
    @Column("requester_user_id")
    val requesterUserId: String,
    
    @Column("request_type")
    val requestType: RequestType,
    
    @Column("swap_shift_id")
    val swapShiftId: String? = null,
    
    @Column("swap_shift_position")
    val swapShiftPosition: String? = null,
    
    @Column("swap_shift_start_time")
    val swapShiftStartTime: OffsetDateTime? = null,
    
    @Column("swap_shift_end_time")
    val swapShiftEndTime: OffsetDateTime? = null,
    
    @Column("requester_user_first_name")
    val requesterUserFirstName: String? = null,
    
    @Column("requester_user_last_name")
    val requesterUserLastName: String? = null,
    
    val status: RequestStatus = RequestStatus.PENDING,
    
    @Column("created_at")
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    
    @Column("updated_at")
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
) {
    fun isSwapRequest(): Boolean {
        return requestType == RequestType.SWAP_SHIFT
    }
    
    fun isTakeRequest(): Boolean {
        return requestType == RequestType.TAKE_SHIFT
    }
    
    fun isPending(): Boolean {
        return status == RequestStatus.PENDING
    }
    
    fun isAcceptedByPoster(): Boolean {
        return status == RequestStatus.ACCEPTED_BY_POSTER
    }
    
    fun isAwaitingManagerApproval(): Boolean {
        return status == RequestStatus.ACCEPTED_BY_POSTER
    }
    
    fun isCompleted(): Boolean {
        return status in listOf(
            RequestStatus.APPROVED_BY_MANAGER,
            RequestStatus.REJECTED_BY_MANAGER,
            RequestStatus.DECLINED_BY_POSTER
        )
    }
    
    init {
        // Validation: swap requests must have a swap_shift_id
        require(
            (requestType == RequestType.SWAP_SHIFT && swapShiftId != null) ||
            (requestType == RequestType.TAKE_SHIFT && swapShiftId == null)
        ) {
            "Swap requests must include a swap_shift_id, take requests must not"
        }
    }
}

enum class RequestType {
    TAKE_SHIFT,
    SWAP_SHIFT
}

enum class RequestStatus {
    PENDING,
    ACCEPTED_BY_POSTER,
    DECLINED_BY_POSTER,
    APPROVED_BY_MANAGER,
    REJECTED_BY_MANAGER,
    COMPLETED,
    PROCESSING_FAILED
}