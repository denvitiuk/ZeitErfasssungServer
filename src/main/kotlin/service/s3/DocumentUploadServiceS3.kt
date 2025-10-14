/*
package com.yourcompany.zeiterfassung.service.s3

import com.yourcompany.zeiterfassung.ports.DocumentUploadService
import com.yourcompany.zeiterfassung.ports.PresignUploadRequest
import com.yourcompany.zeiterfassung.ports.PresignedUpload
import com.yourcompany.zeiterfassung.ports.UploadKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URL
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * S3-based implementation of DocumentUploadService that returns pre-signed PUT URLs.
 *
 * Assumes single-part uploads. For very large files consider switching to multipart flow.
 */
class DocumentUploadServiceS3(
    private val presigner: S3Presigner,
    private val cfg: S3Config
) : DocumentUploadService {

    override suspend fun presignUpload(req: PresignUploadRequest): PresignedUpload = withContext(Dispatchers.IO) {
        val objectKey = buildObjectKey(req.kind)

        val put = PutObjectRequest.builder()
            .bucket(cfg.bucket)
            .key(objectKey)
            .contentType(req.contentType)
            .apply {
                if (cfg.serverSideEncryption != null) this.serverSideEncryption(cfg.serverSideEncryption)
                if (cfg.kmsKeyId != null) this.ssekmsKeyId(cfg.kmsKeyId)
            }
            .build()

        val presign = PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofSeconds(cfg.ttlSeconds))
            .putObjectRequest(put)
            .build()

        val result = presigner.presignPutObject(presign)

        // Flatten signed headers to simple Map<String,String> for the client
        val signedHeaders: MutableMap<String, String> = mutableMapOf(
            "Content-Type" to req.contentType
        )
        result.signedHeaders().forEach { (k, v) ->
            signedHeaders[k] = v.joinToString(",")
        }

        // Explicitly include SSE headers if configured (SDK may or may not include them in signed headers depending on signer)
        if (cfg.serverSideEncryption != null) signedHeaders["x-amz-server-side-encryption"] = cfg.serverSideEncryption
        if (cfg.kmsKeyId != null) signedHeaders["x-amz-server-side-encryption-aws-kms-key-id"] = cfg.kmsKeyId

        PresignedUpload(
            uploadUrl = result.url().toExternalForm(),
            objectKey = objectKey,
            ttlSeconds = cfg.ttlSeconds,
            headers = signedHeaders
        )
    }

    private fun buildObjectKey(kind: UploadKind): String {
        val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) // yyyyMMdd
        val uuid = UUID.randomUUID().toString()
        val base = kind.toString().lowercase(Locale.ROOT)
        val prefix = cfg.prefix.trim('/').let { if (it.isEmpty()) null else it }
        return buildString {
            if (prefix != null) append(prefix).append('/')
            append(base).append('/')
            append(today).append('/')
            append(uuid)
        }
    }

    data class S3Config(
        val bucket: String,
        val region: Region,
        val prefix: String = "uploads",
        val ttlSeconds: Long = 900,
        val serverSideEncryption: String? = null, // e.g. "AES256" or "aws:kms"
        val kmsKeyId: String? = null,
    )

    companion object {
        /**
         * Create a presigner + config from environment variables.
         * AWS credentials resolution is via DefaultCredentialsProvider (env, profile, role, etc.).
         *
         * Required envs:
         *  - S3_BUCKET
         *  - AWS_REGION
         * Optional envs:
         *  - S3_PREFIX (default "uploads")
         *  - S3_PRESIGN_TTL_SECONDS (default 900)
         *  - S3_SSE (e.g. "AES256" or "aws:kms")
         *  - S3_KMS_KEY_ID (if S3_SSE == "aws:kms")
         */
        fun fromEnv(): DocumentUploadServiceS3 {
            val bucket = requireNotNull(System.getenv("S3_BUCKET")) { "S3_BUCKET is required" }
            val regionStr = requireNotNull(System.getenv("AWS_REGION")) { "AWS_REGION is required" }
            val region = Region.of(regionStr)
            val prefix = System.getenv("S3_PREFIX") ?: "uploads"
            val ttlSeconds = (System.getenv("S3_PRESIGN_TTL_SECONDS") ?: "900").toLong()
            val sse = System.getenv("S3_SSE")
            val kmsKeyId = System.getenv("S3_KMS_KEY_ID")

            val presigner = S3Presigner.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()

            return DocumentUploadServiceS3(
                presigner,
                S3Config(
                    bucket = bucket,
                    region = region,
                    prefix = prefix,
                    ttlSeconds = ttlSeconds,
                    serverSideEncryption = sse,
                    kmsKeyId = kmsKeyId
                )
            )
        }
    }
}
*/