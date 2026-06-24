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

data class BackupValidationResult(
    val isValid: Boolean,
    val databaseVersion: Int? = null,
    val errorMessage: String? = null
)

data class BackupResult(
    val location: String,
    val sizeBytes: Long,
    val databaseVersion: Int
)

data class RestoreResult(
    val databaseVersion: Int
)
