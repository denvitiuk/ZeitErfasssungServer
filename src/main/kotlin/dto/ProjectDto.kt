
package com.yourcompany.zeiterfassung.dto

import kotlinx.serialization.Serializable

/**
 * DTOs for Projects and Project Members
 * Keep them flexible (nullable fields for PATCH), simple strings for timestamps.
 */

// ---------------------------------------------------------------------------
// Project core DTOs
// ---------------------------------------------------------------------------

/** Compact item for lists / overviews. */
@Serializable
data class ProjectListItemDTO(
    val id: Int,
    val companyId: Int,
    val title: String,
    val location: String? = null,
    val membersCount: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

/** Full project object; may be used in detail screens or admin views. */
@Serializable
data class ProjectDTO(
    val id: Int,
    val companyId: Int,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val membersCount: Int? = null
)

/** Project + members for a detail endpoint. */
@Serializable
data class ProjectDetailDTO(
    val project: ProjectDTO,
    val members: List<ProjectMemberDTO> = emptyList()
)

// ---------------------------------------------------------------------------
// Requests for create/update
// ---------------------------------------------------------------------------

/** Create a project inside current company (self scope). */
@Serializable
data class ProjectCreateRequest(
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val lat: Double? = null,
    val lng: Double? = null
)

/** Partial update; only non-null fields will be applied. */
@Serializable
data class ProjectUpdateRequest(
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val lat: Double? = null,
    val lng: Double? = null
)

// ---------------------------------------------------------------------------
// Membership DTOs
// ---------------------------------------------------------------------------

/** A project member (optionally include a display name for UI). */
@Serializable
data class ProjectMemberDTO(
    val userId: Int,
    val name: String? = null,
    val role: Int = 0,         // 0=member, 1=manager (kept as Int for simpler JSON)
    val joinedAt: String? = null
)

@Serializable
data class ProjectMemberAddRequest(
    val userId: Int,
    val role: Int? = null
)

@Serializable
data class ProjectMemberRemoveRequest(
    val userId: Int
)

@Serializable
data class ProjectMemberRoleUpdateRequest(
    val role: Int
)

/** Bulk replace or add members to a project. */
@Serializable
data class ProjectMembersBulkSetRequest(
    val userIds: List<Int>
)

// ---------------------------------------------------------------------------
// Generic tiny responses
// ---------------------------------------------------------------------------

@Serializable
data class IdResponse(val id: Int)

@Serializable
data class OkResponse(val ok: Boolean = true)

