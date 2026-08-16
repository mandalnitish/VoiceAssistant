# tv-bridge (deprecated — no longer needed)

This Python bridge is no longer used by the app. TV control now happens
natively from the Kotlin app itself — see
`app/src/main/java/com/example/voiceassistant/tv/` and the updated
`TvBridge.kt`. No PC has to be on for the TV to respond to voice commands
anymore; the phone talks to the TV directly over Wi-Fi using the same
Android TV Remote protocol v2 this bridge used to wrap.

You can delete this whole `tv-bridge/` folder. It's kept here only in case
you want to reference the original Python implementation.
