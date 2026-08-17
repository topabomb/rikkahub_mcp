package net.weero.measix.pilot.ui.pages.setting

import net.weero.measix.pilot.data.datastore.DisplaySetting

private fun <T> selectChanged(baseline: T, edited: T, current: T): T =
    if (edited != baseline) edited else current

/** 把页面相对本地快照的字段变化应用到最新 DisplaySetting，避免覆盖其他页面的并发修改。 */
internal fun mergeDisplaySettingDelta(
    baseline: DisplaySetting,
    edited: DisplaySetting,
    current: DisplaySetting,
): DisplaySetting = current.copy(
    userAvatar = selectChanged(baseline.userAvatar, edited.userAvatar, current.userAvatar),
    userNickname = selectChanged(baseline.userNickname, edited.userNickname, current.userNickname),
    useAppIconStyleLoadingIndicator = selectChanged(
        baseline.useAppIconStyleLoadingIndicator,
        edited.useAppIconStyleLoadingIndicator,
        current.useAppIconStyleLoadingIndicator,
    ),
    showUserAvatar = selectChanged(baseline.showUserAvatar, edited.showUserAvatar, current.showUserAvatar),
    showAssistantBubble = selectChanged(
        baseline.showAssistantBubble,
        edited.showAssistantBubble,
        current.showAssistantBubble,
    ),
    bubbleOpacity = selectChanged(baseline.bubbleOpacity, edited.bubbleOpacity, current.bubbleOpacity),
    showModelIcon = selectChanged(baseline.showModelIcon, edited.showModelIcon, current.showModelIcon),
    showModelName = selectChanged(baseline.showModelName, edited.showModelName, current.showModelName),
    showDateTimeInMessage = selectChanged(
        baseline.showDateTimeInMessage,
        edited.showDateTimeInMessage,
        current.showDateTimeInMessage,
    ),
    showTokenUsage = selectChanged(baseline.showTokenUsage, edited.showTokenUsage, current.showTokenUsage),
    showThinkingContent = selectChanged(
        baseline.showThinkingContent,
        edited.showThinkingContent,
        current.showThinkingContent,
    ),
    autoCloseThinking = selectChanged(
        baseline.autoCloseThinking,
        edited.autoCloseThinking,
        current.autoCloseThinking,
    ),
    showUpdates = selectChanged(baseline.showUpdates, edited.showUpdates, current.showUpdates),
    updateCheckDisabledUntilEpochMillis = selectChanged(
        baseline.updateCheckDisabledUntilEpochMillis,
        edited.updateCheckDisabledUntilEpochMillis,
        current.updateCheckDisabledUntilEpochMillis,
    ),
    showMessageJumper = selectChanged(
        baseline.showMessageJumper,
        edited.showMessageJumper,
        current.showMessageJumper,
    ),
    messageJumperOnLeft = selectChanged(
        baseline.messageJumperOnLeft,
        edited.messageJumperOnLeft,
        current.messageJumperOnLeft,
    ),
    fontSizeRatio = selectChanged(baseline.fontSizeRatio, edited.fontSizeRatio, current.fontSizeRatio),
    enableMessageGenerationHapticEffect = selectChanged(
        baseline.enableMessageGenerationHapticEffect,
        edited.enableMessageGenerationHapticEffect,
        current.enableMessageGenerationHapticEffect,
    ),
    enableMessageGenerationSoundEffect = selectChanged(
        baseline.enableMessageGenerationSoundEffect,
        edited.enableMessageGenerationSoundEffect,
        current.enableMessageGenerationSoundEffect,
    ),
    skipCropImage = selectChanged(baseline.skipCropImage, edited.skipCropImage, current.skipCropImage),
    enableNotificationOnMessageGeneration = selectChanged(
        baseline.enableNotificationOnMessageGeneration,
        edited.enableNotificationOnMessageGeneration,
        current.enableNotificationOnMessageGeneration,
    ),
    enableLiveUpdateNotification = selectChanged(
        baseline.enableLiveUpdateNotification,
        edited.enableLiveUpdateNotification,
        current.enableLiveUpdateNotification,
    ),
    codeBlockAutoWrap = selectChanged(
        baseline.codeBlockAutoWrap,
        edited.codeBlockAutoWrap,
        current.codeBlockAutoWrap,
    ),
    codeBlockAutoCollapse = selectChanged(
        baseline.codeBlockAutoCollapse,
        edited.codeBlockAutoCollapse,
        current.codeBlockAutoCollapse,
    ),
    showLineNumbers = selectChanged(baseline.showLineNumbers, edited.showLineNumbers, current.showLineNumbers),
    ttsOnlyReadQuoted = selectChanged(
        baseline.ttsOnlyReadQuoted,
        edited.ttsOnlyReadQuoted,
        current.ttsOnlyReadQuoted,
    ),
    ttsOnlyReadOutsideBrackets = selectChanged(
        baseline.ttsOnlyReadOutsideBrackets,
        edited.ttsOnlyReadOutsideBrackets,
        current.ttsOnlyReadOutsideBrackets,
    ),
    autoPlayTTSAfterGeneration = selectChanged(
        baseline.autoPlayTTSAfterGeneration,
        edited.autoPlayTTSAfterGeneration,
        current.autoPlayTTSAfterGeneration,
    ),
    ttsToolSequentialPlayback = selectChanged(
        baseline.ttsToolSequentialPlayback,
        edited.ttsToolSequentialPlayback,
        current.ttsToolSequentialPlayback,
    ),
    pasteLongTextAsFile = selectChanged(
        baseline.pasteLongTextAsFile,
        edited.pasteLongTextAsFile,
        current.pasteLongTextAsFile,
    ),
    pasteLongTextThreshold = selectChanged(
        baseline.pasteLongTextThreshold,
        edited.pasteLongTextThreshold,
        current.pasteLongTextThreshold,
    ),
    sendOnEnter = selectChanged(baseline.sendOnEnter, edited.sendOnEnter, current.sendOnEnter),
    enableAutoScroll = selectChanged(baseline.enableAutoScroll, edited.enableAutoScroll, current.enableAutoScroll),
    enableLatexRendering = selectChanged(
        baseline.enableLatexRendering,
        edited.enableLatexRendering,
        current.enableLatexRendering,
    ),
    enableBlurEffect = selectChanged(
        baseline.enableBlurEffect,
        edited.enableBlurEffect,
        current.enableBlurEffect,
    ),
    chatFontFamily = selectChanged(baseline.chatFontFamily, edited.chatFontFamily, current.chatFontFamily),
    chatCustomFontPath = selectChanged(
        baseline.chatCustomFontPath,
        edited.chatCustomFontPath,
        current.chatCustomFontPath,
    ),
    chatCustomFontName = selectChanged(
        baseline.chatCustomFontName,
        edited.chatCustomFontName,
        current.chatCustomFontName,
    ),
    enableVolumeKeyScroll = selectChanged(
        baseline.enableVolumeKeyScroll,
        edited.enableVolumeKeyScroll,
        current.enableVolumeKeyScroll,
    ),
    volumeKeyScrollRatio = selectChanged(
        baseline.volumeKeyScrollRatio,
        edited.volumeKeyScrollRatio,
        current.volumeKeyScrollRatio,
    ),
)
