package me.rerere.tts.controller

/**
 * 为共享播放器上的一次 play() 分配所有权。
 *
 * 新播放取得所有权后，旧播放迟到的取消/回调不得再 stop 播放器或覆盖状态。
 */
internal class PlaybackOwner {
    private var activeToken: Any? = null

    fun claim(): Any = Any().also { activeToken = it }

    fun owns(token: Any): Boolean = activeToken === token

    fun release(token: Any): Boolean {
        if (!owns(token)) return false
        activeToken = null
        return true
    }

    fun invalidate() {
        activeToken = null
    }
}
