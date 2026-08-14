# MirAIe AC Bridge

A small always-on server that lets the voice assistant app control your
Panasonic MirAIe AC. Panasonic has no public official API, so this uses the
community `miraie-ac` Python library (the same one used by the Home Assistant
MirAIe integrations) and exposes it as plain HTTP endpoints.

## 1. Deploy to Render

1. Push this repo (or at least the `server/` folder) to GitHub.
2. On [render.com](https://render.com): **New → Web Service** → connect the repo.
3. Set:
   - **Root Directory:** `server`
   - **Runtime:** Python 3
   - **Build Command:** `pip install -r requirements.txt`
   - **Start Command:** `python app.py`
4. Add environment variables (Render → your service → Environment):
   | Key | Value |
   |---|---|
   | `MIRAIE_MOBILE` | Your MirAIe app login, with country code, e.g. `+91XXXXXXXXXX` |
   | `MIRAIE_PASSWORD` | Your MirAIe app password |
   | `BRIDGE_API_KEY` | Any secret string you make up, e.g. `a1b2c3d4e5` |
5. Deploy. Note the URL Render gives you, e.g. `https://your-app.onrender.com`.

**Free-tier note:** Render's free web services spin down after inactivity and
take ~30–60s to wake on the next request, which also means the AC connection
reconnects. If you want it always warm, use a paid instance or a cron ping.

## 2. Confirm the AC's real method names (one-time)

The `miraie-ac` library only documents `turn_on()` / `turn_off()` publicly —
temperature and mode method names aren't in the README. `app.py` tries a
short list of likely names automatically, but confirm them once:

```
curl -H "X-Api-Key: YOUR_KEY" https://your-app.onrender.com/ac/inspect
```

This returns every public method on your AC device object. If
`/ac/temperature/<n>` or `/ac/mode/<m>` later return
`"no ... method found"`, open `app.py`, find the real name in that list, and
add it to the `try_candidates([...])` call for that endpoint.

## 3. Test it

```
curl -H "X-Api-Key: YOUR_KEY" https://your-app.onrender.com/health

curl -X POST -H "X-Api-Key: YOUR_KEY" https://your-app.onrender.com/ac/on
curl -X POST -H "X-Api-Key: YOUR_KEY" https://your-app.onrender.com/ac/off
curl -X POST -H "X-Api-Key: YOUR_KEY" https://your-app.onrender.com/ac/temperature/24
curl -X POST -H "X-Api-Key: YOUR_KEY" https://your-app.onrender.com/ac/mode/cool
```

## 4. Point the Android app at it

Open `app/src/main/java/com/example/voiceassistant/AcBridge.kt` and set:

```kotlin
private const val BASE_URL = "https://your-app.onrender.com"
private const val API_KEY = "the same BRIDGE_API_KEY value"
```

Then rebuild the app. Voice commands like "turn on the AC", "set AC to 24",
"cool mode" will now hit this server.

## Security notes

- `MIRAIE_MOBILE` / `MIRAIE_PASSWORD` are your real MirAIe account
  credentials — only ever set them as Render environment variables, never
  commit them to the repo.
- `BRIDGE_API_KEY` is what stops a stranger who finds your Render URL from
  controlling your AC. Keep it out of any public repo too (put `AcBridge.kt`'s
  real values in a file you don't commit, or use BuildConfig fields sourced
  from `local.properties` for a more production-grade setup).
- This library is a reverse-engineered, unofficial integration. It could
  break if Panasonic changes their backend — that's an inherent risk, not a
  bug in this bridge.
