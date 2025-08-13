package com.clockwise.colabService.controller

import com.clockwise.colabService.dto.*
import com.clockwise.colabService.service.ShiftMarketplaceService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import jakarta.validation.Valid

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/v1/marketplace")
class ShiftMarketplaceController(
    private val shiftMarketplaceService: ShiftMarketplaceService
) {
    private fun extractUserInfo(authentication: Authentication): Map<String, Any?> {
        val jwt = authentication.principal as Jwt
        return mapOf(
            "userId" to jwt.getClaimAsString("sub"),
            "email" to jwt.getClaimAsString("email"),
            "firstName" to jwt.getClaimAsString("given_name"),
            "lastName" to jwt.getClaimAsString("family_name")
        )
    }
    
    @PostMapping("/shifts/{planningServiceShiftId}")
    @PreAuthorize("hasAnyRole('admin', 'manager', 'employee')")
    fun postShiftToMarketplace(
        @PathVariable planningServiceShiftId: String,
        @Valid @RequestBody request: CreateExchangeShiftRequest,
        authentication: Authentication
    ): Mono<ResponseEntity<ExchangeShiftDto>> {
        val userId = extractUserInfo(authentication)["userId"] as String
        logger.info { "User $userId posting shift $planningServiceShiftId to marketplace" }
        
        return shiftMarketplaceService.postShiftToMarketplace(
            request.copy(planningServiceShiftId = planningServiceShiftId),
            userId
        )
        .map { exchangeShift ->
            ResponseEntity.status(HttpStatus.CREATED)
                .body(ExchangeShiftDto.fromDomain(exchangeShift))
        }
        .onErrorReturn(
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        )
    }
    
    @GetMapping("/shifts")
    @PreAuthorize("hasAnyRole('admin', 'manager', 'employee')")
    fun getAvailableShifts(
        @RequestParam businessUnitId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): Mono<ResponseEntity<ExchangeShiftListResponse>> {
        logger.debug { "Getting available shifts for business unit $businessUnitId, page $page, size $size" }
        
        return shiftMarketplaceService.getAvailableShifts(businessUnitId, page, size)
            .collectList()
            .zipWith(shiftMarketplaceService.countAvailableShifts(businessUnitId))
            .map { tuple ->
                ResponseEntity.ok(
                    ExchangeShiftListResponse(
                        exchangeShifts = tuple.t1.map { ExchangeShiftDto.fromDomain(it) },
                        page = page,
                        size = size,
                        total = tuple.t2
                    )
                )
            }
    }
    
    @GetMapping("/my-shifts")
    @PreAuthorize("hasAnyRole('admin', 'manager', 'employee')")
    fun getMyPostedShifts(authentication: Authentication): Mono<ResponseEntity<List<ExchangeShiftDto>>> {
        val userId = authentication.name
        logger.debug { "Getting posted shifts for user $userId" }
        
        return shiftMarketplaceService.getUserPostedShifts(userId)
            .map { ExchangeShiftDto.fromDomain(it) }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }
    
    @PostMapping("/shifts/{exchangeShiftId}/requests")
    @PreAuthorize("hasAnyRole('admin', 'manager', 'employee')")
    fun submitShiftRequest(
        @PathVariable exchangeShiftId: String,
        @Valid @RequestBody request: CreateShiftRequestRequest,
        authentication: Authentication
    ): Mono<ResponseEntity<ShiftRequestDto>> {
        val userId = authentication.name
        logger.info { "User $userId submitting request for exchange shift $exchangeShiftId" }
        
        return shiftMarketplaceService.submitShiftRequest(exchangeShiftId, request, userId)
            .map { shiftRequest ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .body(ShiftRequestDto.fromDomain(shiftRequest))
            }
            .doOnError { error ->
                logger.error(error) { "Error submitting shift request for exchange shift $exchangeShiftId by user $userId: ${error.message}" }
            }
            .onErrorReturn(
                ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
            )
    }

    
    
    @GetMapping("/my-requests")
    @PreAuthorize("hasAnyRole('admin', 'manager', 'employee')")
    fun getMyRequests(authentication: Authentication): Mono<ResponseEntity<List<ShiftRequestDto>>> {
        val userId = authentication.name
        logger.debug { "Getting shift requests for user $userId" }
        
        return shiftMarketplaceService.getUserRequests(userId)
            .map { ShiftRequestDto.fromDomain(it) }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }
    
    @GetMapping("/my-shifts/{exchangeShiftId}/requests")
    @PreAuthorize("hasAnyRole('admin', 'manager', 'employee')")
    fun getRequestsForMyShift(
        @PathVariable exchangeShiftId: String,
        authentication: Authentication
    ): Mono<ResponseEntity<List<ShiftRequestDto>>> {
        val userId = authentication.name
        logger.debug { "Getting requests for exchange shift $exchangeShiftId by user $userId" }
        
        return shiftMarketplaceService.getRequestsForExchangeShift(exchangeShiftId, userId)
            .map { ShiftRequestDto.fromDomain(it) }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }
    
    @PutMapping("/my-shifts/{exchangeShiftId}/requests/{requestId}/accept")
    @PreAuthorize("hasAnyRole('admin', 'manager', 'employee')")
    fun acceptRequest(
        @PathVariable exchangeShiftId: String,
        @PathVariable requestId: String,
        authentication: Authentication
    ): Mono<ResponseEntity<ShiftRequestDto>> {
        val userId = authentication.name
        logger.info { "User $userId accepting request $requestId for exchange shift $exchangeShiftId" }
        
        return shiftMarketplaceService.acceptRequest(exchangeShiftId, requestId, userId)
            .map { pair ->
                ResponseEntity.ok(ShiftRequestDto.fromDomain(pair.second))
            }
            .onErrorReturn(
                ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
            )
    }
    
    @DeleteMapping("/my-shifts/{exchangeShiftId}")
    @PreAuthorize("hasAnyRole('admin', 'manager', 'employee')")
    fun cancelExchangeShift(
        @PathVariable exchangeShiftId: String,
        authentication: Authentication
    ): Mono<ResponseEntity<ExchangeShiftDto>> {
        val userId = authentication.name
        logger.info { "User $userId cancelling exchange shift $exchangeShiftId" }
        
        return shiftMarketplaceService.cancelExchangeShift(exchangeShiftId, userId)
            .map { exchangeShift ->
                ResponseEntity.ok(ExchangeShiftDto.fromDomain(exchangeShift))
            }
            .onErrorReturn(
                ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
            )
    }
}