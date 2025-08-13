package com.clockwise.colabService.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("posts")
data class Post(
    @Id
    val id: String? = null,
    val title: String,
    val body: String,
    val authorUserId: String,
    val businessUnitId: String,
    val targetAudience: TargetAudience,
    val creatorUserFirstName: String? = null,
    val creatorUserLastName: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    enum class TargetAudience {
        ALL_EMPLOYEES,
        MANAGERS_ONLY
    }
}