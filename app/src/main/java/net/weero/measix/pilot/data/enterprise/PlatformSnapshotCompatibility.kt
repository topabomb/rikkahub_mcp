package net.weero.measix.pilot.data.enterprise

/**
 * Supported wire formats are an explicit set, not a min/max range or the app version.
 * Adding a version requires its generated contract, strict decoder, mapper and consumer tests.
 * Snapshot compatibility gates configuration use; it never establishes or revokes identity.
 */
internal object PlatformSnapshotCompatibility {
    val supportedSchemas: Set<Long> = setOf(4L, 5L)

    /** An intersection permits downloading, but cannot prove that the active release is supported. */
    fun requireAdvertisedSupport(advertised: List<Long>) {
        if (advertised.none { it in supportedSchemas }) {
            throw EnterpriseSnapshotCompatibilityException(advertised)
        }
    }

    /** Check the actual envelope before decoding a body whose fields may belong to a future format. */
    fun requireSupportedVersion(schema: Long) {
        if (schema !in supportedSchemas) throw EnterpriseSnapshotCompatibilityException(listOf(schema))
    }

    /** Canonical disk data may remain readable after its wire decoder is retired. It is not an execution permit. */
    fun requireExecutionSupport(execution: EnterpriseExecution) {
        when (execution) {
            is EnterpriseExecution.Platform -> {
                val schema = execution.snapshotSchemaVersion
                    ?: throw EnterpriseConfigurationException("enterprise_snapshot_version_unverified",
                        "Synchronize enterprise configuration to verify its format before execution.")
                requireSupportedVersion(schema)
            }
        }
    }
}

/** Only parsing and domain validation run here; transport, storage and cancellation keep their own meaning. */
internal inline fun <T> validateSnapshotContent(validation: () -> T): T = try {
    validation()
} catch (unsupported: EnterpriseSnapshotCompatibilityException) {
    throw unsupported
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (invalid: IllegalArgumentException) {
    throw EnterpriseSnapshotContentException(invalid)
} catch (invalid: IllegalStateException) {
    throw EnterpriseSnapshotContentException(invalid)
}

internal class EnterpriseSnapshotContentException(cause: Exception) :
    EnterpriseConfigurationException("enterprise_snapshot_invalid", cause.message) {
    init { initCause(cause) }
}
