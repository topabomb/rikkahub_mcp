package net.weero.measix.pilot.data.enterprise

/** Configuration compatibility does not invalidate an authenticated enterprise identity. */
internal class EnterpriseSnapshotCompatibilityException(
    val receivedSchemas: List<Long>,
    val supportedSchemas: Set<Long> = PlatformSnapshotCompatibility.supportedSchemas,
) : EnterpriseConfigurationException(
    "enterprise_configuration_version_unsupported",
    "The platform provides snapshot schemas $receivedSchemas; this client supports $supportedSchemas.",
)
