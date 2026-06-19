package net.weero.mersix.pilot.data.event

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
}
