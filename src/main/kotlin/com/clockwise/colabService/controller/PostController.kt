package com.clockwise.colabService.controller

import com.clockwise.colabService.dto.*
import com.clockwise.colabService.service.PostService
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
@RequestMapping("/v1/posts")
class PostController(
    private val postService: PostService
) {
    
    private fun extractUserInfo(authentication: Authentication): Map<String, Any?> {
        val jwt = authentication.principal as Jwt
        return mapOf(
            "userId" to jwt.getClaimAsString("sub"),
            "email" to jwt.getClaimAsString("email"),
            "firstName" to jwt.getClaimAsString("given_name"),
            "lastName" to jwt.getClaimAsString("family_name"),
            "roles" to (jwt.getClaimAsStringList("realm_access.roles") ?: emptyList())
        )
    }
    
    private fun extractRoles(authentication: Authentication): Set<String> {
        val jwt = authentication.principal as Jwt
        val realmAccess = jwt.getClaim<Map<String, Any>>("realm_access")
        val roles = realmAccess?.get("roles") as? List<String> ?: emptyList()
        return roles.toSet()
    }
    
    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'manager')")
    fun createPost(
        @Valid @RequestBody request: CreatePostRequest,
        authentication: Authentication
    ): Mono<ResponseEntity<PostDto>> {
        val userInfo = extractUserInfo(authentication)
        val userId = userInfo["userId"] as String
        val firstName = userInfo["firstName"] as String?
        val lastName = userInfo["lastName"] as String?
        val userRoles = extractRoles(authentication)
        
        logger.info { "User $userId creating post '${request.title}' for business unit ${request.businessUnitId}" }
        
        return postService.createPost(request, userId, userRoles, firstName, lastName)
            .map { post ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .body(PostDto.fromDomain(post))
            }
            .onErrorResume { error ->
                logger.error(error) { "Error creating post: ${error.message}" }
                Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build())
            }
    }
    
    @GetMapping("/my-posts")
    @PreAuthorize("hasAnyRole('admin', 'manager')")
    fun getMyPosts(authentication: Authentication): Mono<ResponseEntity<List<PostSummaryDto>>> {
        val userId = extractUserInfo(authentication)["userId"] as String
        val userRoles = extractRoles(authentication)
        
        logger.debug { "Getting posts created by user $userId" }
        
        return postService.getMyPosts(userId, userRoles)
            .map { PostSummaryDto.fromDomain(it) }
            .collectList()
            .map { posts ->
                ResponseEntity.ok(posts)
            }
            .onErrorResume { error ->
                logger.error(error) { "Error getting posts for user $userId: ${error.message}" }
                Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build())
            }
    }
    
    @GetMapping("/{postId}")
    @PreAuthorize("hasAnyRole('admin', 'manager', 'employee')")
    fun getPostById(
        @PathVariable postId: String,
        authentication: Authentication
    ): Mono<ResponseEntity<PostDto>> {
        val userRoles = extractRoles(authentication)
        
        logger.debug { "Getting post by ID: $postId" }
        
        return postService.getPostById(postId, userRoles)
            .map { post ->
                ResponseEntity.ok(PostDto.fromDomain(post))
            }
            .onErrorResume { error ->
                logger.error(error) { "Error getting post $postId: ${error.message}" }
                when (error) {
                    is IllegalArgumentException -> Mono.just(ResponseEntity.notFound().build())
                    is org.springframework.security.access.AccessDeniedException -> 
                        Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build())
                    else -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build())
                }
            }
    }
    
    @GetMapping
    @PreAuthorize("hasAnyRole('admin', 'manager', 'employee')")
    fun getPostsForBusinessUnit(
        @RequestParam businessUnitId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        authentication: Authentication
    ): Mono<ResponseEntity<PostListResponse>> {
        val userRoles = extractRoles(authentication)
        
        logger.debug { "Getting posts for business unit $businessUnitId, page $page, size $size" }
        
        return postService.getPostsForBusinessUnit(businessUnitId, userRoles, page, size)
            .collectList()
            .zipWith(postService.countPostsForBusinessUnit(businessUnitId, userRoles))
            .map { tuple ->
                ResponseEntity.ok(
                    PostListResponse(
                        posts = tuple.t1.map { PostSummaryDto.fromDomain(it) },
                        page = page,
                        size = size,
                        total = tuple.t2
                    )
                )
            }
            .onErrorResume { error ->
                logger.error(error) { "Error getting posts for business unit $businessUnitId: ${error.message}" }
                Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build())
            }
    }
    
    @DeleteMapping("/{postId}")
    @PreAuthorize("hasAnyRole('admin', 'manager')")
    fun deletePost(
        @PathVariable postId: String,
        authentication: Authentication
    ): Mono<ResponseEntity<Void>> {
        val userId = extractUserInfo(authentication)["userId"] as String
        val userRoles = extractRoles(authentication)
        
        logger.info { "User $userId attempting to delete post $postId" }
        
        return postService.deletePost(postId, userId, userRoles)
            .then(Mono.just(ResponseEntity.noContent().build<Void>()))
            .onErrorResume { error ->
                logger.error(error) { "Error deleting post $postId: ${error.message}" }
                when (error) {
                    is IllegalArgumentException -> Mono.just(ResponseEntity.notFound().build())
                    is org.springframework.security.access.AccessDeniedException -> 
                        Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build())
                    else -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build())
                }
            }
    }
}