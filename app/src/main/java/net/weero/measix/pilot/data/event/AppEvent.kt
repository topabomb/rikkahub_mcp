package net.weero.measix.pilot.data.event

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
}
