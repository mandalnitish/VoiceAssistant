# Voice Assistant (Android, Kotlin)

A starter voice-assistant app: tap the mic, speak, and it recognizes your speech,
speaks a reply, and can trigger real device actions (calls, web search, opening apps,
flashlight, time/date, etc).

## How it works

1. **Speech → Text**: Android's built-in `SpeechRecognizer` converts your voice to text.
2. **Command Router**: `MainActivity.handleCommand()` checks the text against known
   phrases (`contains("...")`) and decides what to do.
3. **Action**: it either fires an `Intent` (call, open app, open URL...) and/or
   **Text → Speech** replies out loud via Android's `TextToSpeech`.

There's no cloud AI in this starter — it's pure on-device pattern matching, so it
works offline and has zero API cost. See "Making it smarter" below if you want it
to understand more natural phrasing (like ChatGPT-style assistants such as Siri/Google
Assistant do).

## Setup

1. Install **Android Studio** (Giraffe or newer).
2. Open this folder (`VoiceAssistant/`) as a project — `File > Open`.
3. Let Gradle sync (it will download dependencies automatically).
4. Connect an Android phone (USB debugging on) or start an emulator.
5. Click **Run ▶**.
6. On first run, grant the microphone / call / SMS / camera permissions when prompted.

## Try saying

- "What's the time"
- "Search for cheap laptops"
- "Open YouTube"
- "Open camera"
- "Call 9876543210"
- "Turn on flashlight" / "Turn off flashlight"
- "Hello"

## Project structure

```
VoiceAssistant/
├── app/
│   ├── build.gradle                 # app dependencies
│   └── src/main/
│       ├── AndroidManifest.xml      # permissions + app entry point
│       ├── java/.../MainActivity.kt # all the logic lives here
│       └── res/
│           ├── layout/activity_main.xml
│           ├── drawable/mic_button_bg.xml
│           └── values/strings.xml
├── build.gradle                     # project-level config
└── settings.gradle
```

## Adding new commands

Everything happens in `handleCommand()` inside `MainActivity.kt`. Add a new branch:

```kotlin
command.contains("play music") -> {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com"))
    startActivity(intent)
    speak("Playing music")
}
```

Common things you might want to add next:
- **Set alarms**: use `AlarmClock.ACTION_SET_ALARM` intent.
- **Send actual SMS with content**: parse out a number and message from the sentence
  (the `sendSms()` helper already exists — just call it).
- **Open any installed app by name**: look up installed packages and match by label.
- **Weather / news**: call a weather API (e.g. OpenWeatherMap) from a coroutine and
  speak the result.

## Making it smarter (optional, bigger step)

The current version only matches fixed phrases. If you want it to understand loosely
phrased requests ("hey can you like text mom I'm running late" etc.), the usual approach is:

1. Send the recognized text to an LLM API (e.g. the Anthropic API) with a system prompt
   that asks it to return **structured JSON** like `{"action": "send_sms", "to": "mom", "message": "I'm running late"}`.
2. Parse that JSON in Kotlin and call the matching function (`sendSms`, `makeCall`, etc.)
   instead of the `when` block.

This turns the fixed command router into a flexible one, at the cost of needing
network access and an API key. Happy to build that version next if you want it —
just say the word.

## Permissions note

`CALL_PHONE` and `SEND_SMS` are "dangerous" permissions — Android will show a runtime
permission dialog, and Google Play has extra policy requirements if you publish an app
that uses them (you'd need to declare why in the Play Console). Fine for personal use
and testing; keep that in mind before publishing.
