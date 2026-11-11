# Brick — Android digital assistant

Two voices answer every request:

1. **Brick** (main voice): a natural male voice with a calm, even delivery, in the style of the early
   phone assistants. It says a random line from the response pool you built for that request type.
2. **Unit** (follow-up): a natural female voice from Google's speech engine, the same family of voices
   Google Maps speaks with. It reads the standard result line, such as "Setting timer for 5 minutes.",
   "Today's weather is…" or "Calling Mom.". Neither voice uses robot effects.

Supported request types: timers, alarms, reminders, calendar events, texts, calling, quick internet
search, math, weather, music, maps and directions, opening hours and photography spots.

End a Brick response with `-` (for example `Hold on, let me just-`) and Brick cuts off mid-word, as if
interrupted, and the unit answers right away.

Brick plays on the phone's **assistant volume**, not the media volume.

## Look

The app has a terminal look: near-black with faint scanlines, monospace text and square, thin-bordered
panels, in Brick's blue and amber. The top bar is a prompt (`brick@android:~/assistant$` with a blinking
cursor), the tabs are `[1] assistant`, `[2] responses` and `[3] settings`, and the conversation reads like a
session: `you$` for your requests, `brick>` (amber) for the main voice and `unit>` (blue) for the follow-up.
The listening card has the same terminal look, both inside the app and as the floating card (see below).

**Colors (Settings → Colors):** pick a palette preset (Brick, Phosphor, Amber CRT, Synthwave, Ice, Ember)
and everything follows it: the app, the floating card, the in-app listening card and the intro animation.
Tap a color's swatch for the terminal color picker (`$ pick-color`: old → new preview with its hex code, HSV
or RGB channels as block-cell bars you drag or nudge with `[-]`/`[+]`, a `# wallpaper` row of colors taken from
your home screen wallpaper (its main colors plus the Material You tones Android derives from it), and the 16 ANSI
colors as quick picks),
or type a hex code; changing a built-in preset saves it as a new "(edited)" preset.
`[ export ]` saves the selected palette as a JSON file (`brick-palette-<name>.json`) and `[ import ]` adds a
palette file as a preset and applies it. Your own presets can be deleted.

## Voices

Both voices are Google speech voices (Brick uses Google's speech engine when it's installed), so they
work offline once the voices are downloaded.

- **Main voice (Settings → Main voice → Classic assistant):** an automatic US male voice, slightly deeper,
  with its intonation calmed (Expression 50%). Pick another voice, and set speed, pitch and expression,
  then tap **Hear main voice**. Questions keep their rising ending.
- **Unit voice (Settings → Unit voice → Natural female):** an automatic US female voice, unprocessed.
  If it doesn't match your Google Maps voice, pick another one and tap **Hear unit voice**.
- **Other options:** *Recorded* (a recorded desktop voice stitched from 22,830 recorded words, with
  matched-voice fallback and an optional PC server) and *Robot unit* (the synthetic female unit voice
  with tone shaping) are still available in Settings.


## Floating assistant (draws over other apps)

Make Brick the **default digital assistant** (Settings > Apps > Default apps > Digital assistant app >
Brick). The assist gesture (hold the home button, or swipe up from a bottom corner) then shows Brick's
floating card over whatever app is open, without opening Brick. The **Ask Brick** Quick Settings tile does
the same. The card is a transparent screen over the current app, with see-through status and navigation
bars, so no "Display over other apps" permission is needed. Inside the app, the same animation plays above
the mic button. When Brick is the default assistant, other apps' voice input is forwarded to Google's
recognizer, so it keeps working.

1. Brick listens silently: there's no listening sound, and the speech recognizer's own beeps are muted.
2. Brick and then the unit answer in the card. Then it fades away.
3. Tap the card while listening to finish early. Tap it afterwards to open Brick. Tap outside it to
   dismiss.

When the assistant opens, a one-time **edge-to-edge intro** plays (it doesn't react to your voice). Two
amber-to-cyan light trails race from the bottom center up both screen edges, following the display's
rounded corners, over a warm haze rising from the bottom. They meet at the top, the border flashes and
glows for about a second, then it fades (about 2.5 seconds in all) while the card settles in. The dot-matrix
texture sways and ripples the whole time. If you dismiss Brick before the intro is over, it still plays out
and fades on its own. It never blocks taps: once Brick is dismissed the intro is a see-through overlay that
taps pass straight through (instantly), and the next tap anywhere also fades the intro away right then.

**Tap pass-through while the card is up:** Android doesn't hand a tap outside another app's assistant card to
the app underneath, so by default a tap outside the card just dismisses Brick. Turn on **Brick tap
pass-through** (Settings → Accessibility → Installed apps; Brick's Settings has a shortcut) and Brick catches the
tap or swipe, hides at once, and replays it into the app underneath about 40 ms later, so one tap both dismisses
Brick and does what you meant. The service only performs the gesture you just made; it doesn't read the screen.
On the lock screen taps outside the card only dismiss Brick.

The floating card is a small terminal: a `brick@android:~$` header with a status tag (`[ LISTENING ]`,
`[ ⠋ WORKING ]`, `[ BRICK ]`, `[ UNIT ]`), the session as terminal lines (`you$` with a blinking cursor while
you speak, `brick>` and `unit>` for the replies), and a block-character level meter that follows your voice
or Brick's (a rolling wave while Brick works). The same card shows above the mic button inside the app. The
edge-to-edge intro plays the same as before.

**Typing instead of talking:** tap `[ ⌨ type ]` on the floating card. Brick stops listening, a `you$` type box
opens with the keyboard (the card moves up above it), and Send runs the request. Answers to Brick's questions and
notes can be typed too. The card stays up for the next request; tap outside to close it, or `[ 🎙 talk ]` to go
back to speaking.

Setup: pick Brick under **Default assistant settings**, or add the Ask Brick tile to Quick Settings.
Turn off **Answer over other apps** to open the full app instead. If One UI still opens Brick's main screen,
set the digital assistant app to None and then back to Brick. Older Brick builds were registered differently,
and Brick now forwards those launches to the card either way.

## "Hey Brick" wake word

Turn it on under **Settings → Assistant → "Hey Brick" wake word**. A foreground service runs Vosk's small
English model (Apache 2.0, bundled in `app/src/main/assets/vosk-en`) fully on the phone, restricted to the
phrase "hey brick" with a confidence check on both words. When it hears you, it opens the floating card:
through the assistant session when Brick is the active digital assistant, or directly when "Display over
other apps" is allowed.

- It keeps listening with the screen off or locked (it holds a partial wake lock while the screen is off, so it
  uses more battery). Saying "Hey Brick" then wakes the screen and shows the card over the lock screen.
  Anything that opens another app (Maps, the dialer, Messages) asks you to unlock first, and texts are never
  sent straight from the lock screen.
- It pauses while Brick itself is listening or talking.
- **Battery:** the speech model only runs around sound. A cheap loudness check skips silence (most of the
  day), keeps a 400 ms lead-in so the start of "Hey Brick" isn't lost, and follows the room's noise floor.
  Brick holds no wake lock of its own while listening (Android keeps the CPU awake for the microphone), and the
  pocket sensors report in batches. Measured on the S25 Ultra in a quiet room: about 0.9% of one CPU core,
  down from 7.3%.
- **Microphones:** Hey Brick always listens through the phone's own microphone. Once it wakes, your request
  uses connected Bluetooth headphones' mic (**Settings → Assistant → Use Bluetooth headphones' mic**, on by
  default). Android only opens a headset's mic through its headset (call) link, so the headphones switch to
  that mode just while Brick listens to your request, then go back to normal.
- If Samsung's battery saver stops it overnight, set Brick's battery use to **Unrestricted** in the phone's
  app settings.
- While it's on, a "Listening for Hey Brick" notification and the green mic dot show.
- After restarting the phone, open Brick once to turn it back on. Android doesn't let apps start background
  microphone listening at boot.
- Settings shows its live status, and "Last floating-assistant trigger" reads "hey brick" after it fires.

## Robot unit voice (optional)

With **Settings → Unit voice → Robot unit**, the unit is an **original female voice** with a synthetic "tutorial TTS" feel. An Android female voice
(chosen automatically, or pick one in Settings) is moved to a 215 Hz median pitch while keeping its own
intonation, widened 1.4x. It is then shaped by a tone curve measured from tutorial-bot style TTS: a heavy
low-mid body, a steep fall through 1–5 kHz and a bright 5–10 kHz sheen. No one's voice is copied, and no
game audio is bundled for it.

Adjust it under **Settings → Unit voice** (Pitch, Expressiveness, Tone match, plus optional ring
modulator, metallic comb, speaker filter and bit crush). **Reset unit voice** restores the defaults.
To hear it on the PC, play `samples/robot-preview/unit-female_*.wav` (rendered from Windows' Zira, so the
base voice differs slightly from your phone's).

## Response pools

**Responses** tab → pick a request type from the dropdown → add, edit, delete or preview lines.
You must pick a request type before you can add a line. If a pool is empty, Brick stays quiet for
that type and only the unit answers.

**Export / import:** `[ export ]` saves every pool (request types and rules) to a JSON file you choose, for
example `brick-response-pools-2026-11-08.json`. `[ import ]` opens such a file and, after you confirm,
replaces each pool that's in the file with its lines. Pools not in the file stay as they are, and unknown pool
names are skipped.

**Rules** give specific requests their own lines. After picking **Music** or **Maps & Directions**, a
second dropdown lists the rules:

- **Driver:** play (song, album, artist, playlist, liked songs, anything); audio effects (any effect on or
  off, or each effect on or off: reverb, low-pass, high-pass, equalizer, spatial audio); presets (each
  effect); lyrics; what's playing.
- **Spotify:** play (song, album, artist, playlist, liked songs, anything).
- **Other:** other music apps; pause, resume and skip.
- **Maps:** Google Maps, Waze or another maps app, each split into directions and show a place; plus
  opening and closing times, and photography spots.

Brick speaks from the most specific rule that has lines, then broader ones. For example, "turn on reverb"
tries *Reverb › Turn on*, then *Any effect on*, then *Reverb*, then *Audio effects*, then *Driver*, then all
Music. An empty rule just falls through, and the screen shows which pool will be used instead. The
built-in rule lines are real Brick recordings.

## Building

1. Install [Android Studio](https://developer.android.com/studio) (it includes the SDK and JDK).
2. **File → Open** this folder. Let Gradle sync. Studio downloads Gradle 8.11.1.
3. Rebuild Brick's recorded word bank, which isn't in the repository (at 131 MB it's over GitHub's 100 MB
   file limit): on Windows with the "Microsoft David Desktop" voice installed, run
   `powershell -ExecutionPolicy Bypass -File tools\render-david-words.ps1`. It writes
   `app/src/main/assets/david_words/words.adpcm`. Without it, Brick still builds, but new responses can't be
   stitched from David's recorded words.
4. Plug in your phone with USB debugging on and press **Run**.

The `wear` module is the Wear OS companion app (install it on a paired watch with the same debug key).

Requirements: Android 8.0 (API 26) or newer. Speech input uses the phone's speech recognizer
(the Google app on most phones).

## Example requests

| Type | Say |
|---|---|
| Timer | "Set a timer for 1 hour and 30 minutes" |
| Reminder | "Remind me to take out the trash tonight at 8" / "…in 20 minutes" |
| Calendar | "Add dentist appointment to my calendar on Friday at 3 pm" |
| Text | "Text Mom saying I'm on my way" |
| Call | "Call Dad" / "Call 555 123 4567" |
| Questions | "Who was Ada Lovelace" (answered directly, no web search; if Brick can't answer, the unit says so) |
| Search | "Search for how tall is Mount Everest" / "Look up …" / "Google …" (only when you say so) |
| Apps | "Open Spotify" / "Launch the camera" |
| Time | "What time is it" / "What time is it in Tokyo" / "What's the date" |
| Device | "Turn on the flashlight" / "Volume to 40 percent" / "Turn it up" / "Put my phone on vibrate" / "Turn on Do Not Disturb" / "What's my battery" / "Brightness to 80%" / "Turn on Wi-Fi" |
| Clock | "30 minutes" (timer) / "7:00" or "7 am" (alarm): a bare time always goes to the Clock app. A day said before or after the time ("9:40 tomorrow", "7 on Friday") is kept: without am/pm, another day means the morning. The Clock app only rings at the next occurrence of a time, so for a later day Brick offers a reminder instead |
| Math | "What's 15 percent of 80" / "Square root of 144 plus 3" |
| Weather | "What's the weather" / "Weather in Chicago tomorrow" |
| Music | "Play classic rock" / "Pause" / "Next song" |
| Driver: play | "Play the album Madeira by Delayde" / "Play my road trip playlist" / "Play songs by No Spirit" / "Play Starblessed by No Spirit" / "Play my liked songs" |
| Pick the app | "Play The Prince by Madeon in Driver" / "Play The Prince by Madeon on Spotify" / "Play the album Adventure on Spotify" |
| Driver: effects | "Turn on reverb" / "Turn the low-pass filter off" / "Disable the equalizer" / "Toggle spatial audio" |
| Driver: presets | "Use the Lush Cathedral reverb preset" / "Set the EQ to Bass Heavy" / "Apply my Late Night preset" |
| Driver: lyrics | "Download lyrics" / "Get the lyrics for Starblessed" |
| Driver: status | "What's playing?" |
| Directions | "Navigate to the airport" (opens the Google Maps route; you press Start) |
| Opening hours | "When does Target close?" / "What time does Home Depot open tomorrow?" / "Is the library open?" / "How late is Costco open?" |
| Photography spots | "Show me photography spots in a 50 mile radius" / "Waterfalls within 30 miles" / "Photo spots with flowers near me" |
| Food spots | "Find me a good sandwich place" / "find some good pizza" / "look for Chinese" / "I'm craving sushi" / "I'm hungry" / "good tacos in Austin": Google Maps results near you (or in the place named), best-rated first when you say good/best |
| Shopping and places | "Where can I buy shoes" / "find a good thrift store" / "look for electronics stores" / "find the best mall in Chicago" / "find a gas station" |

## Driver integration

Brick works directly with the **Driver** music player (`com.lostf1sh.pixelplayeross.local`). Driver does the
work and tells Brick what happened, so the robot unit reads Driver's real result, for example
"Applied the Lush Cathedral reverb preset." or "I couldn't find a playlist called Gym in Driver."

- **Play** songs, albums, artists, playlists and liked songs from your Driver library.
- **Audio effects:** reverb, low-pass filter, high-pass filter, graphic equalizer and spatial audio, on or off.
- **Presets:** pick any preset you saved in Driver by name. Say the effect to narrow it down, or leave it out.
- **Lyrics:** download synced lyrics from lrclib.net for the song that's playing or a song you name.
- If Driver isn't open, Brick opens it with your request and Driver carries it out once its library loads.
- Music requests go to Driver when it's installed. To use a different music app, set it under
  **Settings → Music app**. Effects, presets, lyrics and "what's playing" always use Driver.
- Driver accepts these commands from Brick and from apps signed with the same key as Driver.
- Naming the app is optional for Driver-only requests: "turn on reverb in Driver", "enable Driver's
  reverb" and "turn on reverb" all work.
- **Spotify:** Brick finds the exact song, album, artist or playlist, confirms its name with Spotify, and
  opens it so Spotify starts playing. Albums, playlists and artists start from their first track (an artist
  from one of their songs) in that album's, playlist's or artist's context, so the rest follows. "Play my liked songs"
  opens Liked Songs. Without an account connection, items are found with a web lookup and checked against
  Spotify's public page info.
- **Driver → Spotify fallback:** if a song, album, artist or playlist isn't in your Driver library (or Driver
  can't be reached), Brick plays it on Spotify instead and says so.
- **Playlists first:** "play gym" (no song, album or artist named) checks your own Spotify playlists first and
  plays a close match (needs the account connection), then songs, albums and artists.
- **Random start:** albums, playlists, artists and Liked Songs on Spotify start on a random track, then
  continue through that collection.
- **Your own Spotify playlists** need **Settings → Spotify account → Connect Spotify**. Create a free app on
  Spotify's developer dashboard, add the redirect URI `brick://spotify-callback`, tick Web API, and paste
  its Client ID into Brick. Brick signs in with Spotify's standard secure flow (PKCE, no secret) and only
  asks to read your playlists and library. Once connected, it also uses Spotify's own search.
- Optional: notification access (**Settings → Allow Spotify control**) lets Brick nudge Spotify to start
  if a link opens paused.

## Weather details

Ask about the weather in general or one detail, for today, tomorrow, or a named city:

| Ask | Brick answers with |
|---|---|
| "How humid is it" | Humidity (current, or tomorrow's average) |
| "What's the UV index" / "Do I need sunscreen" | UV index, its level (low to extreme) and a sunscreen tip |
| "How windy is it" | Wind speed, direction and gusts |
| "Will it rain tomorrow" | Chance of rain |
| "How much rain will we get" | Expected rainfall (inches or millimeters) |
| "Is it going to snow" | Chance of snow |
| "How much snow tomorrow in Denver" | Expected snowfall (inches or centimeters) |

The general report also includes humidity, wind, and any rain or snow chance of 20% or more.

**Data source:** AccuWeather, when you add your free AccuWeather API key under **Settings → Assistant →
AccuWeather API key** (get one at developer.accuweather.com, then tap **Test key**). Without a key, or if
AccuWeather fails, Brick uses Open-Meteo, which needs no key. AccuWeather's free tier allows a limited
number of calls per day; Brick caches location lookups to save calls.

## Notes

Say "create a note", "take a note" or "note to self". Brick says a line from the **Notes** pool and listens:
say what the note should say, pausing as you like. After **5 seconds of silence** Brick saves it (tap the card,
or `[ listen ]` in the app, to finish early). You can also say it in one go: "take a note that says buy milk
and eggs". Notes use only Brick's voice, never the unit's. The note opens as a new note in **Samsung Notes**
(Google Keep or any notes app that takes shared text otherwise). Brick hears everything near the phone while it
takes dictation, so a word said nearby becomes the note.

## Notifications, email and calendar

| Say | Brick reads |
|---|---|
| "Read my notifications" / "What did I miss?" | The latest 5 notifications: app, sender or title, and text |
| "Check my email" / "Read my emails from John" | New emails showing in your notifications (Gmail, Outlook, Samsung Email…): sender and subject |
| "What's on my calendar today" / "What do I have tomorrow" / "What's on my schedule this week" | Your events with their times |
| "What's my next meeting" | The next event, with its location |

Notifications and email use the notification access Brick already has for Spotify; email is read from the
notifications your mail app shows (no account sign-in), so it covers new mail you haven't dismissed. Calendar
uses the calendar permission. Brick only reads these aloud while the phone is unlocked.

## YouTube

YouTube has its own response pool (**YouTube** in the Responses dropdown) with rules for playing a video,
playing music (YouTube Music) and searching.

"Play lofi hip hop on YouTube" plays the top video (found with a quick web lookup) in the YouTube app; "on
YouTube Music" uses YouTube Music when it's enabled. "Search YouTube for how to fix a bike chain" and "find videos
of cats" open YouTube's search results. Without the YouTube app, the website opens.

## Understanding and listening

- **Loose phrasing:** if the exact patterns don't match, Brick tries again with filler words removed ("um",
  "just", "could you", "real quick", "for me"…) and synonyms swapped ("switch on", "power on" → "turn on"), so
  "could you just switch on the flashlight real quick" works. Casual weather questions ("is it cold outside")
  work too.
- **Driver effects, any wording, several at once:** "turn on the LPF and spatialize audio effects", "can you
  kill the reverb", "I want spatial audio on", "add some echo", "get rid of the low pass and the reverb".
- **Listening starts instantly:** the card starts recording the moment it opens and streams that audio to the
  recognizer, so nothing is lost while the recognizer connects. After "Hey Brick", the wake listener keeps what
  you say next and hands it to the card, so the start of your command isn't cut off.
- **"I like this song":** Brick finds what's playing and in which app, and adds it to that app's likes:
  Driver's favorites (needs the updated Driver), Spotify's Liked Songs (reconnect Spotify once in Settings so
  Brick may save to your library), or any app's own like / heart button.
- **"Add this song to my gym playlist":** Brick finds what's playing and in which app, finds your playlist by
  name there (close spellings work) and adds the song (skipped if it's already in it). Works in Driver (needs the
  updated Driver) and Spotify (your own or collaborative playlists; reconnect Spotify once in Settings so Brick
  may edit playlists). Also "put this on road trip", "save this track to the chill playlist".
- **Every request type has a response pool**, including **Small talk** (greetings, thank-yous and Brick's
  built-in replies), so Brick's main voice always has something to say first.
- **Bluetooth battery:** answers only for the device you ask about: "what's the battery of my watch",
  "...of my headphones" (earbuds count as headphones), "...of my speaker", or a name ("my galaxy buds", "my sony").
  Brick tells kinds apart by the device's name and its Bluetooth type. If several match ("You have two headphones
  connected: A and B. Which one, or all?"), answer with a name, "the first one", or "all"/"both". "Battery of all my
  Bluetooth devices" reads every one. Some watches (e.g. Galaxy Watch) don't share their battery over standard
  Bluetooth; Brick says so. Needs the Nearby devices permission.
- **"Did you mean...?":** when something Brick heard isn't a command but is close to one ("unable LPF", "turn of
  rebirth", "set a tymer"), Brick asks "Did you mean "enable LPF"?"; say yes to run it, no to drop it. Real questions
  aren't affected.

## Discord

Brick works with Discord through its notifications (like Android Auto and watches do), so replies come from your
own account and Brick never needs your Discord login. Needs notification access.

- "Read my Discord messages", "any new Discord DMs", "what did Alex say on Discord".
- "Reply to Alex on Discord saying I'm on my way", "tell Sam on Discord running late", "reply on Discord saying ok"
  (the newest conversation). Works for DMs and channels (name the person or the channel).
- No message given ("message Jordan on Discord"): Brick reads their last message, asks what to say and takes
  dictation. Nothing is sent until you say yes. On the lock screen, unlock first.
- Only conversations showing in your notifications can be read or answered.

## Popular YouTube channels and videos

Saying or searching a big YouTube channel or a hugely watched video ("markiplier", even misspelled, or "never gonna
give you up") opens it in YouTube instead of a web search. Questions ("who is Markiplier") are still answered.

## Brick settings by voice

- "Turn off Hey Brick" / "enable the wake word", "turn off pocket detection", "use the headphone mic",
  "disable the floating card", "turn off app animations", "use the robot voice", "use Celsius" / "switch to
  Fahrenheit". Brick changes the setting just like the Settings screen (Hey Brick starts or stops right away) and
  says if it was already that way. Response pool: **Brick settings**.

## App animations

When Brick uses Spotify, YouTube, Google Maps or Driver, the intro's edge animation plays in that app's colors
(Spotify green, YouTube red, the four Google colors, and a monochrome grayscale for Driver) as the app opens, over it: light trails run around the screen edge and
a thin band of shimmering dots hugs the edges, leaving the middle clear. Requests that don't open the app (like
skipping a Spotify song) play it with the answer. Turn it off with **Settings > App animations** or "turn off app
animations". Needs "Display over other apps".

## Apps, time and device controls

- **Apps:** "open <app>" finds the app by its name on your home screen (close spellings work) and opens it.
- **Time and date:** "what time is it", "what time is it in Tokyo", "what's the date".
- **Flashlight:** on, off or toggle ("flashlight", "turn on the flashlight").
- **Volume** (media): up, down, to a percent, max, mute and unmute.
- **Ringer:** normal, vibrate and silent, and Do Not Disturb on or off. Silent and Do Not Disturb need Do Not
  Disturb access for Brick (Brick opens the setting the first time).
- **Battery:** level, and whether it's charging or plugged in.
- **Brightness:** up, down or to a percent. Needs "Modify system settings" for Brick (opened the first time).
- **Wi-Fi and Bluetooth:** Android doesn't let apps switch these, so Brick opens their controls.

**Weather answers are short:** "what's the temperature" → "72 degrees.", "what's the humidity" → "64 percent
humidity.", "what's the weather" → "72 degrees and partly cloudy. High 75, low 60."

**Hey Brick in a pocket:** with **Pause Hey Brick in a pocket** on (the default), Hey Brick stops listening while
the ambient light sensor reads dark and the proximity sensor is covered, and starts again as soon as the phone
comes out. On the S25 Ultra the proximity reading comes from Samsung's palm-proximity sensor.

## Timers

Timers are set in your phone's default **Clock app**. Say "pause the timer", "resume the timer", "cancel the
timer" (or "... all timers"), or "how much time is left". Android has no public way to pause Clock timers,
so Brick presses the Pause / Resume / Cancel buttons on the Clock app's own timer notification. This uses the
notification access Brick already has for Spotify (**Settings → Allow Spotify control**). Brick remembers the
timers it set, so it can say exactly how much time is left. Without notification access, Brick opens the Clock
app's timers.

## Maps extras

- **Directions** never start on their own. Google Maps opens its route preview, and you press **Start**. Waze opens the place without starting navigation.
- **Opening hours** come from OpenStreetMap: Brick finds the nearest match with posted hours and says
  whether it's open now, when it opens or closes, or today's hours. If no hours are posted, Google Maps
  opens the place page, which shows them.
- **Photography spots** use your location and search Wikidata for waterfalls, botanical gardens and
  arboretums, nature reserves, scenic viewpoints, canyons, national and state parks, lakes and mountains within
  the radius (default 25 miles, up to 100). Well-known places that have photos rank first, and the list mixes
  kinds. Say *waterfalls*, *flowers*/*gardens* or *landscapes*/*views* to narrow it down. Brick opens the
  Google Maps route to the top spot (you press Start) and gives a short description of it. It then asks
  "Would you like to see another location?". Say yes for the next spot's route, or no to stop. From the
  floating card, the card comes back over Maps to hear your answer. If Wikidata doesn't answer, Brick tries
  OpenStreetMap.
## Android Auto

Android Auto doesn't show phone overlays, so the floating card can't appear on the car screen, and the car's
mic button and "Hey Google" always go to Google Assistant. Instead, **Brick has its own screen in the car's app
launcher**:

- Tap **Talk** and speak. Brick listens through the car's microphone (falls back to the phone's), shows what
  you said and both replies, and answers through the car speakers on the assistant volume.
- When Brick asks a question (send this text? start directions?), it listens again for your answer.
- **Directions:** Brick asks "Start directions to ...?" and, on yes, hands the place to the car's navigation
  app (Google Maps on the car screen).
- **Photography spots:** Brick describes each spot. Say *yes* to start directions there, or *next* for another
  location.
- **Texts** are always read back first. On yes, Brick sends them directly in the car (you can't tap send while
  driving), when Brick is allowed to send SMS.

Because Brick isn't on the Play Store, Android Auto only lists it after you turn on unknown sources: open
**Android Auto settings** on the phone, tap **Version** about 10 times to unlock developer settings, then in
the ⋮ menu choose **Developer settings** and turn on **Unknown sources**. Reconnect to the car, and Brick
appears in the app launcher.

To try it without a car, run the Desktop Head Unit on the PC (`android-sdk\extras\google\auto\desktop-head-unit.exe`):
in Android Auto's ⋮ menu choose **Start head unit server**, run `adb forward tcp:5277 tcp:5277`, then start the
head unit.

## Notes

- **Texts:** say it all at once ("text Mom saying I'm on my way"), or just "text Mom" and Brick asks *What
  should the text to Mom say?* and takes it down until 5 seconds of silence. Texts are always read back first:
  Brick asks *Send this text to Mom: ...?* and listens for yes or no. On yes, Brick sends it ("Text sent to
  Mom."). If the phone is locked, it asks you to unlock first and sends right after. Without the SMS permission,
  Messages opens pre-filled instead.
- **Calendar** events go straight into your primary calendar when calendar permission is granted.
  Otherwise your calendar app opens pre-filled.
- **Reminders** are notifications. On Android 12 and newer, tap *Allow on-time reminders* in Settings
  for exact timing.
- To open Brick from the assist gesture, choose it in *Default assistant settings*. Depending on your
  Android version, it shows up as an assist app.
- Web services: Open-Meteo (weather), DuckDuckGo Instant Answers and Wikipedia (search), OpenStreetMap
  Nominatim (opening hours), and Wikidata and Overpass (photography spots). None of them need an API key.
- **Brick's sentences:** stitched words are tuned onto one pitch contour per sentence, so the end falls on
  statements and rises on questions. Short words like *the* and *to* are said quicker and softer, and words are
  crossfaded together instead of separated by gaps.
