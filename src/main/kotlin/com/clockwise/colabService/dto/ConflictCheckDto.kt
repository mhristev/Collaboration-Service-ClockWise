package com.clockwise.colabService.dto

import java.time.OffsetDateTime

data class ScheduleConflictCheckRequest(
    val userId: String,
    val startTime: OffsetDateTime,
    val endTime: OffsetDateTime,
    val correlationId: String
)

data class ScheduleConflictCheckResponse(
    val userId: String,
    val startTime: OffsetDateTime,
    val endTime: OffsetDateTime,
    val hasConflict: Boolean,
    val conflictingShiftIds: List<String> = emptyList(),
    val correlationId: String
)

data class SwapConflictCheckRequest(
    val posterUserId: String,
    val requesterUserId: String,
    val originalShiftId: String,
    val swapShiftId: String,
    val correlationId: String
)

data class SwapConflictCheckResponse(
    val posterUserId: String,
    val requesterUserId: String,
    val originalShiftId: String,
    val swapShiftId: String,
    val posterHasConflict: Boolean,
    val requesterHasConflict: Boolean,
    val posterConflictingShiftIds: List<String> = emptyList(),
    val requesterConflictingShiftIds: List<String> = emptyList(),
    val isSwapPossible: Boolean,
    val correlationId: String
)
