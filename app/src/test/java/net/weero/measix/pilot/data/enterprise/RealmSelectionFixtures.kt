package net.weero.measix.pilot.data.enterprise

internal suspend fun EnterpriseSessionController.selectPersonalFixture(): RealmSelection {
    val original = readPresentation()
    return switchRealm(RealmSwitchRequest(requireNotNull(original.selection), RealmAccess.Personal)) {}
}

internal suspend fun EnterpriseSessionController.selectEnterpriseFixture(): RealmSelection {
    val original = readPresentation()
    val session = requireNotNull((original.state as EnterpriseState.Available).manifest.session)
    val target = RealmAccess.Enterprise(session.identity.scope, session.id)
    return switchRealm(RealmSwitchRequest(requireNotNull(original.selection), target)) {}
}
