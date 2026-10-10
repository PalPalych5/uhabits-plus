package org.isoron.uhabits.backup

data class BackupEntry(
    val name: String,
    val location: String,
    val modifiedAt: Long,
    val sizeBytes: Long,
    val source: BackupSource
)

enum class BackupSource {
    PRIVATE,
    PUBLIC
}

data class BackupStatus(
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val lastBackupLocation: String? = null,
    val lastBackupSizeBytes: Long? = null,
    val lastBackupDatabaseVersion: Int? = null,
    val lastFailureReason: String? = null
)

enum class BackupFormat {
    VERSIONED_ZIP,
    LEGACY_RAW_SQLITE,
    UNKNOWN
}

enum class BackupCompatibilityStatus {
    COMPATIBLE,
    REQUIRES_NEXT_AUTHORITY,
    UNSUPPORTED_VERSION,
    CORRUPTED,
    UNKNOWN
}

data class BackupValidationResult(
    val isValid: Boolean,
    val databaseVersion: Int? = null,
    val errorMessage: String? = null,
    val format: BackupFormat = BackupFormat.UNKNOWN,
    val compatibilityStatus: BackupCompatibilityStatus = BackupCompatibilityStatus.UNKNOWN,
    val manifest: BackupArchiveManifest? = null
)

data class BackupResult(
    val location: String,
    val sizeBytes: Long,
    val databaseVersion: Int
)

data class RestoreResult(
    val databaseVersion: Int
)

sealed class BackupRestoreException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    class IncompatibleAuthorityException(message: String) : BackupRestoreException(message)
    class UnsupportedVersionException(message: String) : BackupRestoreException(message)
    class CorruptedArchiveException(message: String, cause: Throwable? = null) : BackupRestoreException(message, cause)
    class SecurityViolationException(message: String) : BackupRestoreException(message)
    class InsufficientStorageException(message: String) : BackupRestoreException(message)
}
