package com.brick.assistant.data

/**
 * The request types the assistant understands. Each one has its own pool of
 * user-editable David responses, plus an example of the standard robot line
 * that follows David.
 */
enum class Category(
    val label: String,
    val robotExample: String,
    val defaults: List<String>,
) {
    TIMER(
        "Timers",
        "Setting timer for 5 minutes.",
        listOf("Right away.", "On it. The clock is ticking.", "Consider it done."),
    ),
    ALARM(
        "Alarms",
        "Setting alarm for 6:30 AM tomorrow.",
        listOf("Rise and shine, coming right up.", "I will wake you.", "Consider your morning handled."),
    ),
    REMINDER(
        "Reminders",
        "Reminder set: take out the trash, today at 7 PM.",
        listOf("I will make sure you do not forget.", "Noted.", "Writing that down for you."),
    ),
    CALENDAR(
        "Calendar Events",
        "Creating calendar event: dentist appointment, on Friday at 3 PM.",
        listOf("Let me add that to your schedule.", "Your calendar is about to get busier.", "Certainly."),
    ),
    TEXT(
        "Texts",
        "Sending text to Mom: I will be home soon.",
        listOf("Composing your message.", "Of course.", "I will pass that along."),
    ),
    CALL(
        "Calling",
        "Calling Mom.",
        listOf("Connecting you now.", "One moment please.", "Dialing."),
    ),
    SEARCH(
        "Quick Internet Search",
        "Searching for black holes.",
        listOf("Let me look that up.", "Consulting the internet.", "Searching my sources."),
    ),
    MATH(
        "Math Calculations",
        "12 times 8 equals 96.",
        listOf("Let me calculate that.", "Simple arithmetic.", "Crunching the numbers."),
    ),
    WEATHER(
        "Weather",
        "Today's weather is 72 degrees with partly cloudy skies.",
        listOf("Checking the skies.", "Let me look outside for you.", "Here is your forecast."),
    ),
    NIGHT_SKY(
        "Night Sky",
        "Your location is Bortle 6.0, a bright suburban sky.",
        listOf("Looking up.", "Let me read the stars.", "Checking how dark it gets.", "Scanning the night sky."),
    ),
    MUSIC(
        "Music",
        "Playing classic rock.",
        listOf("Excellent choice.", "Music coming right up.", "Let us set the mood."),
    ),
    NAVIGATION(
        "Maps & Directions",
        "Getting directions to the airport.",
        listOf("Let me find the way.", "Plotting a course.", "I will get you there."),
    ),
    YOUTUBE(
        "YouTube",
        "Playing lofi hip hop on YouTube.",
        listOf("To the tube.", "YouTube it is.", "Pulling it up."),
    ),
    CHAT(
        "Small talk",
        "Hello. Awaiting your request.",
        listOf("Hey.", "What's up?", "At your service."),
    ),
    APPS(
        "Open apps",
        "Opening Spotify.",
        listOf("Launching.", "On it.", "Opening it up."),
    ),
    DEVICE(
        "Time & device controls",
        "It's 10:42 PM.",
        listOf("Sure.", "Done.", "Right away."),
    ),
    NOTES(
        "Notes",
        "Saving your note: buy milk and eggs. (Notes use Brick's voice only.)",
        listOf("Ready when you are.", "Go ahead, I'm writing.", "Taking notes."),
    ),
    READING(
        "Notifications, Email & Calendar",
        "You have 3 notifications. Messages, Mom: I'm on my way.",
        listOf("Let me catch you up.", "Here's what you missed.", "Checking."),
    ),
    DISCORD(
        "Discord",
        "Alex said: are you coming tonight?",
        listOf("Checking the server.", "Sliding into the DMs.", "Let me see who's talking."),
    ),
    SETTINGS(
        "Brick settings",
        "Hey Brick is on.",
        listOf("Adjusting myself.", "Tweaking my settings.", "Consider me reconfigured."),
    ),
}
