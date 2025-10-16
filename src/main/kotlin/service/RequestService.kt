
package com.yourcompany.zeiterfassung.service

import com.yourcompany.zeiterfassung.routes.DocumentRequestService as RoutesRequestService
import com.yourcompany.zeiterfassung.routes.CreateRequestPayload as RoutesCreatePayload
import com.yourcompany.zeiterfassung.routes.RequestDTO as RoutesRequestDTO
import com.yourcompany.zeiterfassung.routes.AttachmentRef as RoutesAttachmentRef
import com.yourcompany.zeiterfassung.routes.RequestType as RoutesRequestType
import com.yourcompany.zeiterfassung.routes.RequestStatus as RoutesRequestStatus
import com.yourcompany.zeiterfassung.routes.SetStatusPayload as RoutesSetStatus
import com.yourcompany.zeiterfassung.routes.LeaveBalanceDTO as RoutesLeaveBalance

import com.yourcompany.zeiterfassung.ports.DocumentRequestService as PortsRequestService
import com.yourcompany.zeiterfassung.ports.CreateDocumentRequest as PortsCreateRequest
import com.yourcompany.zeiterfassung.ports.DocumentRequestFull as PortsRequestFull
import com.yourcompany.zeiterfassung.ports.DocumentRequestAttachment as PortsAttachment
import com.yourcompany.zeiterfassung.ports.DocumentType as PortsDocType
import com.yourcompany.zeiterfassung.ports.RequestStatus as PortsStatus
import com.yourcompany.zeiterfassung.ports.SetStatus as PortsSetStatus
import com.yourcompany.zeiterfassung.ports.LeaveBalance as PortsLeaveBalance
import com.yourcompany.zeiterfassung.ports.IncomingAttachment as PortsIncomingAttachment

import java.time.LocalDate

/**
 * Adapter between routes.DocumentRequestService and ports.DocumentRequestService.
 * Converts DTOs/enums both ways.
 */
class RequestService(
    private val delegate: PortsRequestService
) : RoutesRequestService {

    override suspend fun create(userId: Long, companyId: Long, payload: RoutesCreatePayload): RoutesRequestDTO {
        val p = PortsCreateRequest(
            type = PortsDocType.valueOf(payload.type.name),
            dateFrom = LocalDate.parse(payload.dateFrom),
            dateTo = LocalDate.parse(payload.dateTo),
            halfDayStart = payload.halfDayStart,
            halfDayEnd = payload.halfDayEnd,
            note = payload.note,
            attachments = payload.attachments.map { it.toPorts() }
        )
        val full = delegate.create(userId.toInt(), companyId.toInt(), p)
        return full.toRoutes()
    }

    override suspend fun listOwn(userId: Long): List<RoutesRequestDTO> =
        delegate.listOwn(userId.toInt()).map { it.toRoutes() }

    override suspend fun listForCompany(companyId: Long, status: RoutesRequestStatus?): List<RoutesRequestDTO> =
        delegate.listForCompany(companyId.toInt(), status?.let { PortsStatus.valueOf(it.name) }).map { it.toRoutes() }

    override suspend fun setStatus(adminId: Long, companyId: Long, requestId: Long, payload: RoutesSetStatus): RoutesRequestDTO {
        val upd = PortsSetStatus(
            status = PortsStatus.valueOf(payload.status.name),
            reason = payload.reason
        )
        return delegate.setStatus(adminId.toInt(), companyId.toInt(), requestId.toInt(), upd).toRoutes()
    }

    override suspend fun leaveBalance(userId: Long): RoutesLeaveBalance {
        val lb: PortsLeaveBalance = delegate.leaveBalance(userId.toInt(), null)
        return RoutesLeaveBalance(
            totalDaysPerYear = lb.totalDaysPerYear,
            usedDays = lb.usedDays,
            pendingDays = lb.pendingDays,
            remainingDays = lb.remainingDays
        )
    }

    override suspend fun adminLeaveBalance(targetUserId: Long, year: Int): RoutesLeaveBalance {
        val lb: PortsLeaveBalance = delegate.leaveBalance(targetUserId.toInt(), year)
        return RoutesLeaveBalance(
            totalDaysPerYear = lb.totalDaysPerYear,
            usedDays = lb.usedDays,
            pendingDays = lb.pendingDays,
            remainingDays = lb.remainingDays
        )
    }

    override suspend fun adjustLeaveEntitlement(
        adminId: Long,
        companyId: Long,
        targetUserId: Long,
        year: Int,
        deltaDays: Int,
        reason: String?
    ): RoutesLeaveBalance {
        // TEMP compatibility: ports.DocumentRequestService has no adjust API yet.
        // TODO: add `adjustLeaveEntitlement(...)` to ports + implementation and wire it here.
        // For now, return current balance as a no-op so routes compile.
        val lb: PortsLeaveBalance = delegate.leaveBalance(targetUserId.toInt(), year)
        return RoutesLeaveBalance(
            totalDaysPerYear = lb.totalDaysPerYear,
            usedDays = lb.usedDays,
            pendingDays = lb.pendingDays,
            remainingDays = lb.remainingDays
        )
    }
}

// ---------------- Mappers ----------------

private fun PortsRequestFull.toRoutes(): RoutesRequestDTO =
    RoutesRequestDTO(
        id = request.id.toLong(),
        userId = request.userId.toLong(),
        companyId = request.companyId.toLong(),
        type = RoutesRequestType.valueOf(request.type.name),
        status = RoutesRequestStatus.valueOf(request.status.name),
        dateFrom = request.dateFrom.toString(),
        dateTo = request.dateTo.toString(),
        halfDayStart = request.halfDayStart,
        halfDayEnd = request.halfDayEnd,
        note = request.note,
        attachments = attachments.map { it.toRoutes() },
        createdAt = request.createdAt.toEpochMilli(),
        updatedAt = (request.updatedAt ?: request.createdAt).toEpochMilli(),
        declineReason = request.declineReason
    )

private fun PortsAttachment.toRoutes(): RoutesAttachmentRef =
    RoutesAttachmentRef(
        objectKey = storageKey,
        fileName = fileName ?: "",
        contentType = contentType ?: "application/octet-stream",
        size = sizeBytes ?: 0
    )

private fun com.yourcompany.zeiterfassung.routes.AttachmentRef.toPorts(): PortsIncomingAttachment =
    PortsIncomingAttachment(
        objectKey = objectKey,
        fileName = fileName,
        contentType = contentType,
        sizeBytes = size
    )

