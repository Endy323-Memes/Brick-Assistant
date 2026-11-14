# Brick update log

## 1.4

### New features
- **Field guide:** ask about animals, plants, bugs, fish and sea life in plain words. Covers lifespan, diet, habitat, predators, size, bloom times, toxic or safe, bites and stings, pest tips, life cycles, swim speed, dive depth, fresh or salt water, and breathing.
- **Sports:** scores, results, schedules and champions.
- **History:** events, people, and what happened on this day.
- **More lookups:** characters, creators, franchises, YouTubers, phone numbers, rankings, news, new releases, prices and deals.
- **Phobia filters** (Settings › Phobia filters): block searches about spiders, insects, snakes, sharks, the deep sea, holes, mice, birds, dogs, clowns, blood, needles or vomit. Each filter can allow the search without pictures, or show a web link instead.
- **Brick History:** a companion app that saves your searches so repeat questions answer offline. Tap "Don't save" on a card to keep it out.

### Improvements
- Compact answer cards.
- Better weather and Govee light handling.
- "Hey Brick" matches more reliably.

## 1.3

### New features
- **Lyrics matched with Spotify:** when a song plays in Spotify and your account is connected, Brick looks it up in Spotify's catalog, by its track ID when Spotify shares it, or by title, artist and length. It then asks lrclib.net for that exact version's lyrics (a radio edit, a remaster). If nothing matches, Brick falls back to the usual lookup. The card says `MATCH exact version, by Spotify` when this was used.
- **Lyrics kept on time with Spotify:** Android's media controls report Spotify's position only now and then. While timed lyrics are on screen and the song plays, Brick asks Spotify for the exact position and corrects the timing by the difference.
  - To stay within Spotify's rate limit, Brick sends one request at a time, every 20 seconds, or every 45 once the timing holds steady.
  - After a seek or play/pause it checks again soon, but never within 5 seconds of its last request.
  - When Spotify says to slow down, Brick waits as long as Spotify asks, then checks half as often.
  - It ignores slow replies and replies about another song.
  - **Reconnect Spotify in Settings once to turn this on.** It needs the new "currently playing" permission; until then Brick keeps the usual timing.

### Changes
- A break in the singing shows `♪ ♪ ♪` in the current-line box instead of an empty box.

### Improvements
- The lyrics card and the full-screen lyrics redraw every time the screen refreshes, instead of being capped at 30 frames a second, so they move smoothly. They only slow down while the song is paused and nothing moves.

## 1.2

### New features

**Lyrics for the song playing now**
- Ask for them in plain words: "show me the lyrics", "what are the words to this song", "what is he singing", "lyrics to *song* by *artist*".
- They show on a small terminal-style card that follows the song, line by line or word by word when the lyrics are timed that way. The line playing now sits in a box with a percent and a `[#####.....]` bar. Tap a line to jump the song there.
- Brick uses the best copy it can find: a `.lrc` file on the phone (only when Brick may read all files), then lrclib.net.
- The card stays open while you read. Tap outside it to close it.
- **Full screen** opens the lyrics in Brick's own screen, with a seek bar, the time, and Play/Pause. It only opens for the song that's playing, and closes itself if the song changes. The screen stays on while the song plays.

**Countdowns**
- "How many days until Christmas", "how long until Friday", "countdown to Halloween", "days since New Year's", "how many sleeps until Christmas".
- Works for 38 holidays (Easter included), dates, weekdays, clock times and events on your calendar.
- Shows a countdown card with how far along the wait is.
- Brick can tell a countdown from a normal search: "the twelve days of christmas" is searched instead.

**Looking things up**
- People, movies, shows, apps, companies and more, with a fact card and a picture: "who is *actor*", "what's *show*", "the app Discord".

**Movies, shows and songs**
- Ratings, where to watch, where to buy, where to listen, and song credits: "what is Dune rated", "where can I watch *show*", "who produced this song".
- Asking just "where can I watch" makes Brick ask "Which movie or show?".
- Optional free TMDB key in Settings for exact, current streaming listings and TMDB ratings.

**Unit conversion**
- "How many milliseconds in a second", "5 feet to inches". Covers length, weight, time, volume, area, speed, data, temperature, energy, pressure and angle, with nearby units on the card.

**Spelling**
- "How do you spell necessary": big spaced letters read out one by one, a fix if the word was misspelled, and words that sound the same.

**Phone settings**
- Switch, check or find a setting in loose wording: "turn on airplane mode", "is my hotspot on", "where is NFC", "rotation lock on", "light mode".
- Brick switches airplane mode, power saving and Do Not Disturb itself when it's the default assistant, and opens the setting's page otherwise.
- "Show my phone settings" lists every setting with its state and switches.

**Smart home**
- Govee lights on, off, brightness and color (needs a Govee API key in Settings).
- "Open Google Home", SmartThings, Alexa, Philips Hue, Kasa or Home Assistant.

**Quiet When**
- Answers in text only, with no voice, when it's dark, or when it's bright daylight. Both use the light sensor and only apply without headphones. Never on in the car.
- You can set the light level.

**Mute button on the card**
- Tap it to silence both voices. Long-press to mute only Brick or only the unit. A voice that's talking stops at once, and the lines still show as text.

**Questions sound like questions**
- Lines ending in "?" end on a rising pitch, in every voice.

### Changes
- Searches keep your words as said ("the twelve days of christmas", not "the 12 days of christmas").
- A bare topic gets a lookup of what it is.
- "I can't do that yet." is now only for commands Brick can't carry out ("order a pizza").
- If nothing turns up, Brick says it couldn't find a quick answer.
- Every answer now comes with a card. Answers without a card of their own (directions, apps, notes, texts, small talk, errors) get a summary of what Brick understood and what it said, with a button to do it again where that makes sense.
- Calendar search finds events by any of their words: "my vacation" finds "Vacation in Maine".
- "Hey Brick" pauses while battery saver is on, or under 15% battery when not charging.

### Removals
- The old ADPCM David word bank is gone. It was replaced by the new Opus bank below.

### Optimizations
- **New David voice bank:** 16 kHz Opus, much smaller than the old bank for the same words. It's decoded on the phone with the built-in decoder, reused between words.
- The answer card redraws only about once a second while nothing on it is moving, which saves battery.
- Lookups, ratings, listings and lyrics are kept for 10 minutes so a repeat is instant, then deleted.
- The low-battery check for "Hey Brick" runs every 30 seconds instead of constantly.

### Improvements
- Lyric scrolling: flicks coast like a normal list, the view glides back to the current line about 4 seconds after you stop, and the box around the current line grows and shrinks smoothly instead of making the lines jump.
- Saved voice lines from older builds are cleared once, so every line uses the new bank and the question rise.
- Settings that can't both be on (Quiet When in the dark and in daylight) turn each other off.

This build doesn't include the night-sky grid (its data can't be redistributed); see the README to build it yourself.
