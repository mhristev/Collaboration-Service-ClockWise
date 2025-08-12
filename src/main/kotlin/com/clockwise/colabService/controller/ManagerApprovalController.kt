package com.clockwise.colabService.controller

import com.clockwise.colabService.dto.*
import com.clockwise.colabService.service.ShiftMarketplaceService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/v1/marketplace/manager")
class ManagerApprovalController(
    private val shiftMarketplaceService: ShiftMarketplaceService
) {
    
    private fun extractBusinessUnitId(authentication: Authentication): String? {
        val jwt = authentication.principal as Jwt
        // Try to get businessUnitId from JWT claims
        return jwt.getClaimAsString("business_unit_id") 
            ?: jwt.getClaimAsString("businessUnitId")
            ?: jwt.getClaim<List<String>>("business_units")?.firstOrNull()
    }
    
    private fun extractUserInfo(authentication: Authentication): Map<String, Any?> {
        val jwt = authentication.principal as Jwt
        return mapOf(
            "userId" to jwt.getClaimAsString("sub"),
            "email" to jwt.getClaimAsString("email"),
            "businessUnitId" to extractBusinessUnitId(authentication)
        )
    }
    
    @GetMapping("/pending-approvals")
    @PreAuthorize("hasAnyRole('admin', 'manager')")
    fun getPendingApprovals(
        authentication: Authentication,
        @RequestParam(required = false) businessUnitId: String?
    ): Mono<ResponseEntity<List<ShiftRequestDto>>> {
        val userInfo = extractUserInfo(authentication)
        val effectiveBusinessUnitId = businessUnitId ?: userInfo["businessUnitId"] as String?
        
        if (effectiveBusinessUnitId == null) {
            logger.warn { "No business unit ID found in JWT token or request parameter for user ${userInfo["email"]}" }
            return Mono.just(ResponseEntity.badRequest().build())
        }
        
        logger.debug { "Getting pending manager approvals for business unit $effectiveBusinessUnitId by user ${userInfo["email"]}" }
        
        return shiftMarketplaceService.getPendingManagerApprovals(effectiveBusinessUnitId)
            .map { ShiftRequestDto.fromDomain(it) }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }
    
    @GetMapping("/awaiting-approval")
    @PreAuthorize("hasAnyRole('admin', 'manager')")
    fun getAwaitingApprovalExchanges(
        authentication: Authentication,
        @RequestParam(required = false) businessUnitId: String?
    ): Mono<ResponseEntity<List<ExchangeShiftWithRequestDto>>> {
        val userInfo = extractUserInfo(authentication)
        val effectiveBusinessUnitId = businessUnitId ?: userInfo["businessUnitId"] as String?
        
        if (effectiveBusinessUnitId == null) {
            logger.warn { "No business unit ID found in JWT token or request parameter for user ${userInfo["email"]}" }
            return Mono.just(ResponseEntity.badRequest().build())
        }
        
        logger.debug { "Getting exchange shifts awaiting manager approval for business unit $effectiveBusinessUnitId by user ${userInfo["email"]}" }
        
        return shiftMarketplaceService.getAwaitingManagerApprovalExchanges(effectiveBusinessUnitId)
            .map { pair ->
                ExchangeShiftWithRequestDto(
                    exchangeShift = ExchangeShiftDto.fromDomain(pair.first),
                    acceptedRequest = ShiftRequestDto.fromDomain(pair.second)
                )
            }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }
    
    @PutMapping("/requests/{requestId}/approve")
    @PreAuthorize("hasAnyRole('admin', 'manager')")
    fun approveRequest(
        @PathVariable requestId: String
    ): Mono<ResponseEntity<ShiftRequestDto>> {
        logger.info { "Manager approving request $requestId" }
        
        return shiftMarketplaceService.approveRequest(requestId)
            .map { shiftRequest ->
                ResponseEntity.ok(ShiftRequestDto.fromDomain(shiftRequest))
            }
            .onErrorReturn(
                ResponseEntity.badRequest().build()
            )
    }
    
    @PutMapping("/requests/{requestId}/reject")
    @PreAuthorize("hasAnyRole('admin', 'manager')")
    fun rejectRequest(
        @PathVariable requestId: String
    ): Mono<ResponseEntity<ShiftRequestDto>> {
        logger.info { "Manager rejecting request $requestId" }
        
        return shiftMarketplaceService.rejectRequest(requestId)
            .map { shiftRequest ->
                ResponseEntity.ok(ShiftRequestDto.fromDomain(shiftRequest))
            }
            .onErrorReturn(
                ResponseEntity.badRequest().build()
            )
    }
    
    @PutMapping("/exchanges/{exchangeShiftId}")
    @PreAuthorize("hasAnyRole('admin', 'manager')")
    fun updateExchangeShiftStatus(
        @PathVariable exchangeShiftId: String,
        @RequestBody request: UpdateExchangeShiftStatusRequest,
        authentication: Authentication,
        @RequestParam(required = false) businessUnitId: String?
    ): Mono<ResponseEntity<ExchangeShiftWithRequestDto>> {
        val userInfo = extractUserInfo(authentication)
        val effectiveBusinessUnitId = businessUnitId ?: userInfo["businessUnitId"] as String?
        
        if (effectiveBusinessUnitId == null) {
            logger.warn { "No business unit ID found in JWT token or request parameter for user ${userInfo["email"]}" }
            return Mono.just(ResponseEntity.badRequest().build())
        }
        
        logger.info { "Manager ${userInfo["email"]} updating exchange shift $exchangeShiftId status to ${request.status} for business unit $effectiveBusinessUnitId" }
        
        return shiftMarketplaceService.updateExchangeShiftStatus(exchangeShiftId, request.status, effectiveBusinessUnitId)
            .map { pair ->
                ResponseEntity.ok(
                    ExchangeShiftWithRequestDto(
                        exchangeShift = ExchangeShiftDto.fromDomain(pair.first),
                        acceptedRequest = ShiftRequestDto.fromDomain(pair.second)
                    )
                )
            }
            .onErrorReturn(
                ResponseEntity.badRequest().build()
            )
    }
}