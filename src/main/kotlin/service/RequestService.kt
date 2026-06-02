package com.yourcompany.zeiterfassung.service

import com.yourcompany.zeiterfassung.routes.DocumentRequestService as RoutesRequestService
import com.yourcompany.zeiterfassung.routes.CreateRequestPayload as RoutesCreatePayload
import com.yourcompany.zeiterfassung.routes.RequestDTO as RoutesRequestDTO
import com.yourcompany.zeiterfassung.routes.AttachmentRef as RoutesAttachmentRef
import com.yourcompany.zeiterfassung.routes.RequestType as RoutesRequestType
import com.yourcompany.zeiterfassung.routes.RequestStatus as RoutesRequestStatus
import com.yourcompany.zeiterfassung.routes.SetStatusPayload as RoutesSetStatus
import com.yourcompany.zeiterfassung.routes.SubmitDocumentSignaturePayload as RoutesSubmitDocumentSignature
import com.yourcompany.zeiterfassung.routes.DocumentRequestSignatureDTO as RoutesDocumentRequestSignatureDTO
import com.yourcompany.zeiterfassung.routes.DocumentSignatureRole as RoutesDocumentSignatureRole
import com.yourcompany.zeiterfassung.routes.DocumentRequestEventDTO as RoutesDocumentRequestEventDTO
import com.yourcompany.zeiterfassung.routes.DocumentRequestEventType as RoutesDocumentRequestEventType
import com.yourcompany.zeiterfassung.routes.DocumentSignatureSettingsDTO as RoutesDocumentSignatureSettingsDTO
import com.yourcompany.zeiterfassung.routes.UpdateDocumentSignatureSettingsPayload as RoutesUpdateDocumentSignatureSettings
import com.yourcompany.zeiterfassung.routes.LeaveBalanceDTO as RoutesLeaveBalance

import com.yourcompany.zeiterfassung.ports.DocumentRequestService as PortsRequestService
import com.yourcompany.zeiterfassung.ports.CreateDocumentRequest as PortsCreateRequest
import com.yourcompany.zeiterfassung.ports.DocumentRequestFull as PortsRequestFull
import com.yourcompany.zeiterfassung.ports.DocumentRequestAttachment as PortsAttachment
import com.yourcompany.zeiterfassung.ports.DocumentType as PortsDocType
import com.yourcompany.zeiterfassung.ports.RequestStatus as PortsStatus
import com.yourcompany.zeiterfassung.ports.SetStatus as PortsSetStatus
import com.yourcompany.zeiterfassung.ports.SubmitDocumentSignature as PortsSubmitDocumentSignature
import com.yourcompany.zeiterfassung.ports.DocumentSignatureRole as PortsDocumentSignatureRole
import com.yourcompany.zeiterfassung.ports.DocumentRequestSignature as PortsDocumentRequestSignature
import com.yourcompany.zeiterfassung.ports.DocumentRequestEvent as PortsDocumentRequestEvent
import com.yourcompany.zeiterfassung.ports.DocumentSignatureSettings as PortsDocumentSignatureSettings
import com.yourcompany.zeiterfassung.ports.UpdateDocumentSignatureSettings as PortsUpdateDocumentSignatureSettings
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
        return full.toRoutesWithSignatures(viewerUserId = userId.toInt())
    }

    override suspend fun listOwn(userId: Long): List<RoutesRequestDTO> =
        delegate.listOwn(userId.toInt()).map { full ->
            full.toRoutesWithSignatures(viewerUserId = userId.toInt())
        }

    override suspend fun listForCompany(companyId: Long, status: RoutesRequestStatus?): List<RoutesRequestDTO> =
        delegate.listForCompany(companyId.toInt(), status?.let { PortsStatus.valueOf(it.name) }).map { full ->
            full.toRoutesWithSignatures(viewerUserId = 0)
        }

    override suspend fun setStatus(adminId: Long, companyId: Long, requestId: Long, payload: RoutesSetStatus): RoutesRequestDTO {
        val upd = PortsSetStatus(
            status = PortsStatus.valueOf(payload.status.name),
            reason = payload.reason
        )
        return delegate
            .setStatus(adminId.toInt(), companyId.toInt(), requestId.toInt(), upd)
            .toRoutesWithSignatures(viewerUserId = adminId.toInt())
    }

    override suspend fun signRequest(
        signerUserId: Long,
        companyId: Long,
        requestId: Long,
        payload: RoutesSubmitDocumentSignature,
        ipAddress: String?,
        userAgent: String?
    ): RoutesDocumentRequestSignatureDTO {
        val portsPayload = PortsSubmitDocumentSignature(
            signerRole = PortsDocumentSignatureRole.valueOf(payload.signerRole.name),
            signatureImageBase64 = payload.signatureImageBase64,
            deviceInfo = payload.deviceInfo
        )

        return delegate.signRequest(
            signerUserId = signerUserId.toInt(),
            companyId = companyId.toInt(),
            requestId = requestId.toInt(),
            payload = portsPayload,
            ipAddress = ipAddress,
            userAgent = userAgent
        ).toRoutes()
    }

    override suspend fun listSignatures(
        userId: Long,
        companyId: Long,
        requestId: Long
    ): List<RoutesDocumentRequestSignatureDTO> =
        delegate.listSignatures(
            userId = userId.toInt(),
            companyId = companyId.toInt(),
            requestId = requestId.toInt()
        ).map { it.toRoutes() }

    override suspend fun listEvents(
        userId: Long,
        companyId: Long,
        requestId: Long
    ): List<RoutesDocumentRequestEventDTO> =
        delegate.listEvents(
            userId = userId.toInt(),
            companyId = companyId.toInt(),
            requestId = requestId.toInt()
        ).map { it.toRoutes() }

    override suspend fun getSignatureSettings(companyId: Long): RoutesDocumentSignatureSettingsDTO =
        delegate.getSignatureSettings(companyId.toInt()).toRoutes()

    override suspend fun updateSignatureSettings(
        adminId: Long,
        companyId: Long,
        payload: RoutesUpdateDocumentSignatureSettings
    ): RoutesDocumentSignatureSettingsDTO {
        val portsPayload = PortsUpdateDocumentSignatureSettings(
            signaturesEnabled = payload.signaturesEnabled,
            signaturesRequired = payload.signaturesRequired,
            workerSignatureRequired = payload.workerSignatureRequired,
            adminSignatureRequired = payload.adminSignatureRequired
        )

        return delegate.updateSignatureSettings(
            adminId = adminId.toInt(),
            companyId = companyId.toInt(),
            payload = portsPayload
        ).toRoutes()
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

    private suspend fun PortsRequestFull.toRoutesWithSignatures(
        viewerUserId: Int
    ): RoutesRequestDTO {
        val signatures = delegate.listSignatures(
            userId = viewerUserId,
            companyId = request.companyId,
            requestId = request.id
        ).map { it.toRoutes() }

        return toRoutes(signatures = signatures)
    }
}


private fun PortsRequestFull.toRoutes(
    signatures: List<RoutesDocumentRequestSignatureDTO> = emptyList()
): RoutesRequestDTO =
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
        signatures = signatures,
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

private fun PortsDocumentRequestSignature.toRoutes(): RoutesDocumentRequestSignatureDTO =
    RoutesDocumentRequestSignatureDTO(
        id = id,
        requestId = requestId.toLong(),
        companyId = companyId.toLong(),
        signerUserId = signerUserId.toLong(),
        signerRole = RoutesDocumentSignatureRole.valueOf(signerRole.name),
        signatureImageBlobId = signatureImageBlobId,
        signatureImageUrl = signatureImageBlobId?.let { "/files/pg/$it" },
        documentSnapshotHash = documentSnapshotHash,
        ipAddress = ipAddress,
        userAgent = userAgent,
        deviceInfo = deviceInfo,
        signedAt = signedAt.toEpochMilli(),
        createdAt = createdAt.toEpochMilli()
    )

private fun PortsDocumentRequestEvent.toRoutes(): RoutesDocumentRequestEventDTO =
    RoutesDocumentRequestEventDTO(
        id = id,
        requestId = requestId.toLong(),
        companyId = companyId.toLong(),
        actorUserId = actorUserId?.toLong(),
        eventType = RoutesDocumentRequestEventType.valueOf(eventType.name),
        metadata = metadata,
        createdAt = createdAt.toEpochMilli()
    )

private fun PortsDocumentSignatureSettings.toRoutes(): RoutesDocumentSignatureSettingsDTO =
    RoutesDocumentSignatureSettingsDTO(
        companyId = companyId.toLong(),
        signaturesEnabled = signaturesEnabled,
        signaturesRequired = signaturesRequired,
        workerSignatureRequired = workerSignatureRequired,
        adminSignatureRequired = adminSignatureRequired,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli()
    )

private fun com.yourcompany.zeiterfassung.routes.AttachmentRef.toPorts(): PortsIncomingAttachment =
    PortsIncomingAttachment(
        objectKey = objectKey,
        fileName = fileName,
        contentType = contentType,
        sizeBytes = size
    )
