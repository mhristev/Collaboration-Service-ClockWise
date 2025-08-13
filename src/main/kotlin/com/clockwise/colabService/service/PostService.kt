package com.clockwise.colabService.service

import com.clockwise.colabService.domain.Post
import com.clockwise.colabService.dto.CreatePostRequest
import com.clockwise.colabService.repository.PostRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class PostService(
    private val postRepository: PostRepository
) {
    
    fun createPost(request: CreatePostRequest, authorUserId: String, userRoles: Set<String>, firstName: String?, lastName: String?): Mono<Post> {
        // Verify that the user has permission to create posts (admin or manager)
        if (!userRoles.any { it in listOf("admin", "manager") }) {
            return Mono.error(AccessDeniedException("Only admins and managers can create posts"))
        }
        
        val post = Post(
            title = request.title,
            body = request.body,
            authorUserId = authorUserId,
            businessUnitId = request.businessUnitId,
            targetAudience = request.targetAudience,
            creatorUserFirstName = firstName,
            creatorUserLastName = lastName,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        logger.info { "Creating post '${request.title}' for business unit ${request.businessUnitId} by user $authorUserId" }
        return postRepository.save(post)
    }
    
    fun getPostsForBusinessUnit(businessUnitId: String, userRoles: Set<String>, page: Int, size: Int): Flux<Post> {
        val offset = (page * size).toLong()
        
        return if (userRoles.any { it in listOf("admin", "manager") }) {
            // Managers and admins can see all posts
            logger.debug { "Retrieving all posts for business unit $businessUnitId (manager/admin view)" }
            postRepository.findAllPosts(businessUnitId, size, offset)
        } else {
            // Employees can only see posts targeted to all employees
            logger.debug { "Retrieving employee posts for business unit $businessUnitId (employee view)" }
            postRepository.findAllEmployeePosts(businessUnitId, size, offset)
        }
    }
    
    fun countPostsForBusinessUnit(businessUnitId: String, userRoles: Set<String>): Mono<Long> {
        return if (userRoles.any { it in listOf("admin", "manager") }) {
            postRepository.countAllPosts(businessUnitId)
        } else {
            postRepository.countAllEmployeePosts(businessUnitId)
        }
    }
    
    fun getMyPosts(authorUserId: String, userRoles: Set<String>): Flux<Post> {
        // Only admins and managers can create posts, so only they can have "my posts"
        if (!userRoles.any { it in listOf("admin", "manager") }) {
            return Flux.empty()
        }
        
        logger.debug { "Retrieving posts created by user $authorUserId" }
        return postRepository.findByAuthorUserId(authorUserId)
    }
    
    fun getPostById(postId: String, userRoles: Set<String>): Mono<Post> {
        logger.debug { "Retrieving post by ID: $postId" }
        
        return postRepository.findById(postId)
            .switchIfEmpty(Mono.error(IllegalArgumentException("Post not found")))
            .flatMap { post ->
                // Apply business rules based on user roles and target audience
                when {
                    // Admins and managers can see all posts
                    userRoles.any { it in listOf("admin", "manager") } -> Mono.just(post)
                    // Employees can only see posts targeted to all employees
                    userRoles.contains("employee") && post.targetAudience == Post.TargetAudience.ALL_EMPLOYEES -> Mono.just(post)
                    // Otherwise, access denied
                    else -> Mono.error(AccessDeniedException("You don't have permission to view this post"))
                }
            }
    }
    
    fun deletePost(postId: String, userId: String, userRoles: Set<String>): Mono<Void> {
        return postRepository.findById(postId)
            .switchIfEmpty(Mono.error(IllegalArgumentException("Post not found")))
            .flatMap { post ->
                // Only the author or admins can delete posts
                if (post.authorUserId == userId || userRoles.contains("admin")) {
                    logger.info { "Deleting post $postId by user $userId" }
                    postRepository.deleteById(postId)
                } else {
                    Mono.error(AccessDeniedException("You can only delete your own posts"))
                }
            }
    }
}