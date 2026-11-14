# Brick — Android digital assistant

Brick is a voice assistant for Android with a terminal look and a Wear OS companion. Every answer comes in
two voices: **Brick**, a calm male voice that says a line from your own response pools, then the **unit**, a
natural female voice that reads the result ("Setting timer for 5 minutes."). Both play on the assistant volume.

**Download:** [brick.apk](https://github.com/Endy323-Memes/Brick-Assistant/releases/latest/download/brick.apk)
(Android 8.0+). Install it, then make Brick the default digital assistant (Settings › Apps › Default apps ›
Digital assistant app).

## What it does

- **Floating card:** the assist gesture, the Ask Brick tile or "Hey Brick" opens a terminal-style card over any
  app. Talk or type, tap outside to close. Long answers scroll inside the card, and readouts (weather week,
  night sky, stopwatch…) get their own card above it, sized to the screen.
- **"Hey Brick" wake word:** fully on-device (Vosk), works with the screen off, pauses in a pocket, about 1% of
  a CPU core.
- **Offline speech:** uses Android's on-device recognizer when it's available, so most requests work without a
  connection.
- **Requests:** timers, alarms, reminders, calendar, texts (always read back first, never sent from the lock
  screen), calls, math, time, apps, device controls (flashlight, volume, ringer, brightness, battery), notes,
  notifications, email, Discord replies, questions and web search.
- **Weather:** today, tomorrow, this week or the weekend, or one detail (UV, wind, rain, snow). Sources:
  AccuWeather, Pirate Weather, Visual Crossing, WeatherAPI.com, OpenWeatherMap (each with your own key), the
  US National Weather Service or Open-Meteo (no key). If a source fails, Brick falls back to Open-Meteo. Say
  "hide my location" or "obscure my location" to keep the place out of the answer.
- **Music:** works with Spotify (exact matches, your own playlists after connecting), YouTube and YouTube Music,
  with lyrics for the song playing now.
- **Maps:** directions (Google Maps opens the route, you press Start), opening hours, food and shops,
  photography spots, and **saved places**: save "home" with an address and say "take me home". Places can be
  renamed, imported and exported.
- **Night sky:** Bortle class, visible stars, the Milky Way, and the darkest sky nearby, offline from a sky grid
  you build yourself (see below).
- **Quiet outdoors:** above a light level you pick (0–500 lux), Brick answers in text only, unless headphones or
  the car are connected.
- **Android Auto:** Brick has its own screen in the car launcher (turn on Unknown sources in Android Auto's
  developer settings).

## Make it yours

- **Responses tab:** add, edit and preview Brick's lines for each request type. Rules give specific requests
  (e.g. *Animals › Bugs and insects*) their own lines, falling back to broader pools. Export and import as JSON.
- **Settings:** voices (speed, pitch, expression), color palettes (presets, a color picker, import and export),
  weather source and keys, saved places, wake word, quiet outdoors and more. Many settings can also be changed
  by voice ("turn off Hey Brick", "use Celsius").

## Building

1. Open the folder in [Android Studio](https://developer.android.com/studio) and let Gradle sync.
2. Optional: rebuild the recorded word bank (131 MB, too big for GitHub) on Windows with the Microsoft David
   voice: `powershell -ExecutionPolicy Bypass -File tools\render-david-words.ps1`.
3. Optional: build the night-sky grid (below).
4. Run on a phone with USB debugging. The `wear` module is the watch app (same debug key).

### Night-sky grid

The grid comes from the 2015 World Atlas of Artificial Night Sky Brightness, whose terms restrict
redistribution. It isn't in this repository or the published APK, so without it night-sky answers have no
data. To build it:

1. Download `World_Atlas_2015.zip` from the [GFZ data page](https://doi.org/10.5880/GFZ.1.4.2016.001) and
   extract `World_Atlas_2015.tif`.
2. `java -Xmx4g tools/sky-grid/BuildSkyGrid.java World_Atlas_2015.tif sky_grid_raw.bin.gz 6 85.0541668645001 -180 0.00833333`
3. Mask the sea with Natural Earth's [1:10m Land](https://www.naturalearthdata.com/downloads/10m-physical-vectors/10m-land/)
   polygons: `java -Xmx2g tools/sky-grid/MaskSea.java sky_grid_raw.bin.gz ne_10m_land.shp app/src/main/assets/sky/sky_grid.bin.gz`

Data: Falchi, F. et al. (2016), *Supplement to: The New World Atlas of Artificial Night Sky Brightness*, GFZ
Data Services, https://doi.org/10.5880/GFZ.1.4.2016.001 (paper: Science Advances 2(6):e1600377). Non-commercial
use only. Values are modeled estimates, not measurements.

## Services

Open-Meteo and the National Weather Service (weather), DuckDuckGo and Wikipedia (answers), OpenStreetMap
(places and hours), and Wikidata and Overpass (photo spots) need no key. AccuWeather and the other weather
sources, and the Spotify account connection, use your own free keys.
