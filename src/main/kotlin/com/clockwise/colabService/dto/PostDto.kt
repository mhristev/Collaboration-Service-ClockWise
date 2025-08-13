package com.clockwise.colabService.dto

import com.clockwise.colabService.domain.Post
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class PostDto(
    val id: String,
    val title: String,
    val body: String,
    val authorUserId: String,
    val businessUnitId: String,
    val targetAudience: Post.TargetAudience,
    val creatorUserFirstName: String? = null,
    val creatorUserLastName: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun fromDomain(post: Post): PostDto {
            return PostDto(
                id = post.id!!,
                title = post.title,
                body = post.body,
                authorUserId = post.authorUserId,
                businessUnitId = post.businessUnitId,
                targetAudience = post.targetAudience,
                creatorUserFirstName = post.creatorUserFirstName,
                creatorUserLastName = post.creatorUserLastName,
                createdAt = post.createdAt,
                updatedAt = post.updatedAt
            )
        }
    }
}

data class PostSummaryDto(
    val id: String,
    val title: String,
    val authorUserId: String,
    val businessUnitId: String,
    val targetAudience: Post.TargetAudience,
    val creatorUserFirstName: String? = null,
    val creatorUserLastName: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun fromDomain(post: Post): PostSummaryDto {
            return PostSummaryDto(
                id = post.id!!,
                title = post.title,
                authorUserId = post.authorUserId,
                businessUnitId = post.businessUnitId,
                targetAudience = post.targetAudience,
                creatorUserFirstName = post.creatorUserFirstName,
                creatorUserLastName = post.creatorUserLastName,
                createdAt = post.createdAt,
                updatedAt = post.updatedAt
            )
        }
    }
}

data class CreatePostRequest(
    @field:NotBlank(message = "Title is required")
    @field:Size(max = 200, message = "Title must be less than 200 characters")
    val title: String,
    
    @field:NotBlank(message = "Body is required")
    @field:Size(max = 5000, message = "Body must be less than 5000 characters")
    val body: String,
    
    @field:NotBlank(message = "Business unit ID is required")
    val businessUnitId: String,
    
    val targetAudience: Post.TargetAudience
)

data class PostListResponse(
    val posts: List<PostSummaryDto>,
    val page: Int,
    val size: Int,
    val total: Long
)