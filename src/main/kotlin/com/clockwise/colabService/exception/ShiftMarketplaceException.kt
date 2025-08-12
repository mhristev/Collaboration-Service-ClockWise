package com.clockwise.colabService.exception

sealed class ShiftMarketplaceException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    
    class ShiftAlreadyPostedException(shiftId: String) : 
        ShiftMarketplaceException("Shift $shiftId is already posted to marketplace")
    
    class ShiftNotFound(shiftId: String) : 
        ShiftMarketplaceException("Exchange shift $shiftId not found")
    
    class ShiftNotOwnedException(shiftId: String, userId: String) : 
        ShiftMarketplaceException("Exchange shift $shiftId is not owned by user $userId")
    
    class ShiftNotAcceptingRequestsException(shiftId: String) : 
        ShiftMarketplaceException("Exchange shift $shiftId is not accepting requests")
    
    class RequestAlreadyExistsException(shiftId: String, userId: String) : 
        ShiftMarketplaceException("User $userId already has a request for exchange shift $shiftId")
    
    class RequestNotFound(requestId: String) : 
        ShiftMarketplaceException("Shift request $requestId not found")
    
    class RequestNotOwnedException(requestId: String, userId: String) : 
        ShiftMarketplaceException("Shift request $requestId is not owned by user $userId")
    
    class RequestInvalidStateException(requestId: String, currentState: String, expectedState: String) : 
        ShiftMarketplaceException("Request $requestId is in state $currentState, expected $expectedState")
    
    class CannotRequestOwnShiftException(userId: String) : 
        ShiftMarketplaceException("User $userId cannot request their own shift")
    
    class ShiftCannotBeModifiedException(shiftId: String, currentState: String) : 
        ShiftMarketplaceException("Exchange shift $shiftId cannot be modified in state $currentState")
    
    class InvalidSwapRequestException(message: String) : 
        ShiftMarketplaceException("Invalid swap request: $message")
    
    class BusinessUnitAccessException(userId: String, businessUnitId: String) : 
        ShiftMarketplaceException("User $userId does not have access to business unit $businessUnitId")
    
    class KafkaPublishException(message: String, cause: Throwable) : 
        ShiftMarketplaceException("Failed to publish event to Kafka: $message", cause)
}