package com.clockwise.colabService.dto

import com.clockwise.colabService.domain.RequestStatus
import com.clockwise.colabService.domain.RequestType
import com.clockwise.colabService.domain.ShiftRequest
import java.time.OffsetDateTime

data class ShiftRequestDto(
    val id: String,
    val exchangeShiftId: String,
    val requesterUserId: String,
    val requestType: RequestType,
    val swapShiftId: String? = null,
    val swapShiftPosition: String? = null,
    val swapShiftStartTime: OffsetDateTime? = null,
    val swapShiftEndTime: OffsetDateTime? = null,
    val requesterUserFirstName: String? = null,
    val requesterUserLastName: String? = null,
    val status: RequestStatus,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
) {
    companion object {
        fun fromDomain(shiftRequest: ShiftRequest): ShiftRequestDto {
            return ShiftRequestDto(
                id = shiftRequest.id!!,
                exchangeShiftId = shiftRequest.exchangeShiftId,
                requesterUserId = shiftRequest.requesterUserId,
                requestType = shiftRequest.requestType,
                swapShiftId = shiftRequest.swapShiftId,
                swapShiftPosition = shiftRequest.swapShiftPosition,
                swapShiftStartTime = shiftRequest.swapShiftStartTime,
                swapShiftEndTime = shiftRequest.swapShiftEndTime,
                requesterUserFirstName = shiftRequest.requesterUserFirstName,
                requesterUserLastName = shiftRequest.requesterUserLastName,
                status = shiftRequest.status,
                createdAt = shiftRequest.createdAt,
                updatedAt = shiftRequest.updatedAt
            )
        }
    }
}

data class CreateShiftRequestRequest(
    val requestType: RequestType,
    val swapShiftId: String? = null,
    val swapShiftPosition: String? = null,
    val swapShiftStartTime: OffsetDateTime? = null,
    val swapShiftEndTime: OffsetDateTime? = null,
    val requesterUserFirstName: String? = null,
    val requesterUserLastName: String? = null
)

data class ShiftRequestListResponse(
    val shiftRequests: List<ShiftRequestDto>,
    val page: Int,
    val size: Int,
    val total: Long
)

data class AcceptRequestRequest(
    val requestId: String
)

data class ManagerApprovalRequest(
    val approved: Boolean,
    val reason: String? = null
)

data class ShiftExchangeEventDto(
    val requestId: String,
    val exchangeShiftId: String,
    val originalShiftId: String,
    val posterUserId: String,
    val requesterUserId: String,
    val requestType: RequestType,
    val swapShiftId: String? = null,
    val businessUnitId: String,
    val timestamp: OffsetDateTime = OffsetDateTime.now()
)

data class ExchangeShiftWithRequestDto(
    val exchangeShift: ExchangeShiftDto,
    val acceptedRequest: ShiftRequestDto
)

data class UpdateExchangeShiftStatusRequest(
    val status: String // "APPROVED" or "REJECTED"
)