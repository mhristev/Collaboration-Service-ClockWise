package com.clockwise.colabService.repository

import com.clockwise.colabService.domain.Post
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface PostRepository : ReactiveCrudRepository<Post, String> {
    
    @Query("""
        SELECT * FROM posts 
        WHERE business_unit_id = :businessUnitId 
        AND target_audience = 'ALL_EMPLOYEES' 
        ORDER BY created_at DESC 
        LIMIT :size OFFSET :offset
    """)
    fun findAllEmployeePosts(businessUnitId: String, size: Int, offset: Long): Flux<Post>
    
    @Query("""
        SELECT * FROM posts 
        WHERE business_unit_id = :businessUnitId 
        ORDER BY created_at DESC 
        LIMIT :size OFFSET :offset
    """)
    fun findAllPosts(businessUnitId: String, size: Int, offset: Long): Flux<Post>
    
    @Query("""
        SELECT COUNT(*) FROM posts 
        WHERE business_unit_id = :businessUnitId 
        AND target_audience = 'ALL_EMPLOYEES'
    """)
    fun countAllEmployeePosts(businessUnitId: String): Mono<Long>
    
    @Query("""
        SELECT COUNT(*) FROM posts 
        WHERE business_unit_id = :businessUnitId
    """)
    fun countAllPosts(businessUnitId: String): Mono<Long>
    
    @Query("""
        SELECT * FROM posts 
        WHERE author_user_id = :authorUserId 
        ORDER BY created_at DESC
    """)
    fun findByAuthorUserId(authorUserId: String): Flux<Post>
}