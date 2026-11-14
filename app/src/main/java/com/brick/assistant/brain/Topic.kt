package com.brick.assistant.brain

/**
 * A one-purpose request, started by Battery's gestures (the card's `brick_topic` extra). The night sky and photo
 * spots run straight away; the others listen for just the thing to look for ("Spotify: say a song"), so
 * "bohemian rhapsody" plays on Spotify without having to say "play ... on Spotify".
 */
enum class Topic(val id: String, val prompt: String, val hint: String, val instant: String? = null) {
    NIGHT_SKY("night_sky", "Night sky", "", instant = "night sky report"),
    PHOTO_SPOTS("photo_spots", "Photo spots", "", instant = "show me photography spots near me"),
    SPOTIFY("spotify", "Spotify", "say a song, artist or playlist"),
    DRIVER("driver", "Driver", "say a song, artist or album"),
    YOUTUBE("youtube", "YouTube", "say what to search for"),
    DIRECTIONS("directions", "Directions", "say where to"),
    ;

    /** What you said (or typed) for this topic, as the request Brick runs. */
    fun request(said: String): Request {
        val t = IntentParser.clean(said).trim()
        val own = IntentParser.parse(said)
        return when (this) {
            NIGHT_SKY -> own as? Request.Sky ?: Request.Sky()
            PHOTO_SPOTS -> own as? Request.PhotoSpots ?: IntentParser.parse("show me photography spots $t") as? Request.PhotoSpots ?: own
            SPOTIFY, DRIVER -> {
                val app = if (this == SPOTIFY) MusicApp.SPOTIFY else MusicApp.DRIVER
                val q = stripLead(t, "(?:play|put on|shuffle|listen to|search(?: for)?|find|look up)")
                val music = IntentParser.parse("play $q") as? Request.Music
                music?.copy(app = app) ?: Request.Music(MusicCommand.PLAY_QUERY, q, app = app)
            }
            YOUTUBE -> {
                val play = Patterns.get("^(?:play|watch|put on)\\b", true).containsMatchIn(t)
                val q = stripLead(t, "(?:play|watch|put on|search(?: for)?|find|look up|show me)")
                    .replace(Patterns.get("\\s+(?:on|in)\\s+youtube$", true), "")
                Request.YouTube(q, play = play, music = false)
            }
            DIRECTIONS -> {
                val q = stripLead(t, "(?:navigate|directions|get directions|give me directions|take me|drive|go|route me)(?:\\s+to)?")
                Request.Navigate(q.replace(Patterns.get("^(?:the|my)\\s+", true), ""), directions = true)
            }
        }
    }

    companion object {
        fun of(id: String?): Topic? = entries.firstOrNull { it.id == id }

        private fun stripLead(s: String, lead: String) =
            s.replace(Regex("^$lead\\s+", RegexOption.IGNORE_CASE), "").trim().ifEmpty { s }
    }
}
