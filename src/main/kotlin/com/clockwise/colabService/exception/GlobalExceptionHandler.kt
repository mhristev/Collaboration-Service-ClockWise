package com.clockwise.colabService.exception

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import reactor.core.publisher.Mono
import java.time.OffsetDateTime

private val logger = KotlinLogging.logger {}

data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val timestamp: OffsetDateTime = OffsetDateTime.now(),
    val path: String? = null
)

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ShiftMarketplaceException.ShiftAlreadyPostedException::class)
    fun handleShiftAlreadyPosted(ex: ShiftMarketplaceException.ShiftAlreadyPostedException): Mono<ResponseEntity<ErrorResponse>> {
        logger.warn { ex.message }
        return Mono.just(
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                    ErrorResponse(
                        status = HttpStatus.CONFLICT.value(),
                        error = "Shift Already Posted",
                        message = ex.message ?: "Shift is already posted to marketplace"
                    )
                )
        )
    }

    @ExceptionHandler(
        ShiftMarketplaceException.ShiftNotFound::class,
        ShiftMarketplaceException.RequestNotFound::class
    )
    fun handleNotFound(ex: ShiftMarketplaceException): Mono<ResponseEntity<ErrorResponse>> {
        logger.warn { ex.message }
        return Mono.just(
            ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                    ErrorResponse(
                        status = HttpStatus.NOT_FOUND.value(),
                        error = "Resource Not Found",
                        message = ex.message ?: "Requested resource not found"
                    )
                )
        )
    }

    @ExceptionHandler(
        ShiftMarketplaceException.ShiftNotOwnedException::class,
        ShiftMarketplaceException.RequestNotOwnedException::class,
        ShiftMarketplaceException.BusinessUnitAccessException::class
    )
    fun handleForbidden(ex: ShiftMarketplaceException): Mono<ResponseEntity<ErrorResponse>> {
        logger.warn { ex.message }
        return Mono.just(
            ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(
                    ErrorResponse(
                        status = HttpStatus.FORBIDDEN.value(),
                        error = "Access Denied",
                        message = ex.message ?: "Access denied to this resource"
                    )
                )
        )
    }

    @ExceptionHandler(
        ShiftMarketplaceException.ShiftNotAcceptingRequestsException::class,
        ShiftMarketplaceException.RequestAlreadyExistsException::class,
        ShiftMarketplaceException.RequestInvalidStateException::class,
        ShiftMarketplaceException.CannotRequestOwnShiftException::class,
        ShiftMarketplaceException.ShiftCannotBeModifiedException::class,
        ShiftMarketplaceException.InvalidSwapRequestException::class
    )
    fun handleBadRequest(ex: ShiftMarketplaceException): Mono<ResponseEntity<ErrorResponse>> {
        logger.warn { ex.message }
        return Mono.just(
            ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                    ErrorResponse(
                        status = HttpStatus.BAD_REQUEST.value(),
                        error = "Bad Request",
                        message = ex.message ?: "Invalid request"
                    )
                )
        )
    }

    @ExceptionHandler(ShiftMarketplaceException.KafkaPublishException::class)
    fun handleKafkaPublishException(ex: ShiftMarketplaceException.KafkaPublishException): Mono<ResponseEntity<ErrorResponse>> {
        logger.error(ex) { "Kafka publish failed: ${ex.message}" }
        return Mono.just(
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                    ErrorResponse(
                        status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        error = "Internal Server Error",
                        message = "Failed to process request due to messaging system error"
                    )
                )
        )
    }

    @ExceptionHandler(WebExchangeBindException::class)
    fun handleValidationException(ex: WebExchangeBindException): Mono<ResponseEntity<ErrorResponse>> {
        val errors = ex.bindingResult.fieldErrors
            .map { "${it.field}: ${it.defaultMessage}" }
            .joinToString(", ")
        
        logger.warn { "Validation failed: $errors" }
        return Mono.just(
            ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                    ErrorResponse(
                        status = HttpStatus.BAD_REQUEST.value(),
                        error = "Validation Failed",
                        message = "Invalid input: $errors"
                    )
                )
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): Mono<ResponseEntity<ErrorResponse>> {
        logger.warn { ex.message }
        return Mono.just(
            ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                    ErrorResponse(
                        status = HttpStatus.BAD_REQUEST.value(),
                        error = "Bad Request",
                        message = ex.message ?: "Invalid request parameter"
                    )
                )
        )
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(ex: IllegalStateException): Mono<ResponseEntity<ErrorResponse>> {
        logger.warn { ex.message }
        return Mono.just(
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                    ErrorResponse(
                        status = HttpStatus.CONFLICT.value(),
                        error = "State Conflict",
                        message = ex.message ?: "Operation not allowed in current state"
                    )
                )
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): Mono<ResponseEntity<ErrorResponse>> {
        logger.error(ex) { "Unexpected error occurred: ${ex.message}" }
        return Mono.just(
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                    ErrorResponse(
                        status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        error = "Internal Server Error",
                        message = "An unexpected error occurred"
                    )
                )
        )
    }
}