package com.clockwise.colabService.dto

import com.clockwise.colabService.domain.ExchangeShift
import com.clockwise.colabService.domain.ExchangeShiftStatus
import java.time.OffsetDateTime

data class ExchangeShiftDto(
    val id: String,
    val planningServiceShiftId: String,
    val posterUserId: String,
    val businessUnitId: String,
    val status: ExchangeShiftStatus,
    val acceptedRequestId: String? = null,
    val shiftPosition: String? = null,
    val shiftStartTime: OffsetDateTime? = null,
    val shiftEndTime: OffsetDateTime? = null,
    val userFirstName: String? = null,
    val userLastName: String? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
) {
    companion object {
        fun fromDomain(exchangeShift: ExchangeShift): ExchangeShiftDto {
            return ExchangeShiftDto(
                id = exchangeShift.id!!,
                planningServiceShiftId = exchangeShift.planningServiceShiftId,
                posterUserId = exchangeShift.posterUserId,
                businessUnitId = exchangeShift.businessUnitId,
                status = exchangeShift.status,
                acceptedRequestId = exchangeShift.acceptedRequestId,
                shiftPosition = exchangeShift.shiftPosition,
                shiftStartTime = exchangeShift.shiftStartTime,
                shiftEndTime = exchangeShift.shiftEndTime,
                userFirstName = exchangeShift.userFirstName,
                userLastName = exchangeShift.userLastName,
                createdAt = exchangeShift.createdAt,
                updatedAt = exchangeShift.updatedAt
            )
        }
    }
}

data class CreateExchangeShiftRequest(
    val planningServiceShiftId: String,
    val businessUnitId: String,
    val shiftPosition: String? = null,
    val shiftStartTime: OffsetDateTime? = null,
    val shiftEndTime: OffsetDateTime? = null,
    val userFirstName: String? = null,
    val userLastName: String? = null
)

data class ExchangeShiftListResponse(
    val exchangeShifts: List<ExchangeShiftDto>,
    val page: Int,
    val size: Int,
    val total: Long
)