package com.brick.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PersonalTest {
    @Test
    fun parsesNotes() {
        assertEquals(Request.Note(null), IntentParser.parse("Hey Brick, create a note"))
        assertEquals(Request.Note(null), IntentParser.parse("take a note"))
        assertEquals(Request.Note("Buy milk and eggs"), IntentParser.parse("take a note that says buy milk and eggs"))
        assertEquals(Request.Note("Call the dentist on Monday"), IntentParser.parse("Note to self: call the dentist on Monday."))
    }

    @Test
    fun parsesReading() {
        assertEquals(Request.ReadNotifications(false, null), IntentParser.parse("read my notifications"))
        assertEquals(Request.ReadNotifications(false, null), IntentParser.parse("what did I miss?"))
        assertEquals(Request.ReadNotifications(true, null), IntentParser.parse("check my email"))
        assertEquals(Request.ReadNotifications(true, "John"), IntentParser.parse("read my emails from John"))
        assertEquals(Request.ReadCalendar(CalendarRange.TODAY), IntentParser.parse("what's on my calendar today"))
        assertEquals(Request.ReadCalendar(CalendarRange.TOMORROW), IntentParser.parse("what do I have tomorrow"))
        assertEquals(Request.ReadCalendar(CalendarRange.NEXT), IntentParser.parse("what's my next meeting"))
        assertEquals(Request.ReadCalendar(CalendarRange.WEEK), IntentParser.parse("what's on my schedule this week"))
        // Creating events still works
        assertTrue(IntentParser.parse("add dentist appointment to my calendar on Friday at 3 pm") is Request.CalendarEvent)
    }

    @Test
    fun parsesLooseNotificationsAndEmail() {
        fun r(t: String) = IntentParser.parse(t)
        val email = Request.ReadNotifications(true, null)
        val notes = Request.ReadNotifications(false, null)
        assertEquals(email, r("any new emails?"))
        assertEquals(email, r("do I have any mail"))
        assertEquals(email, r("what's in my inbox"))
        assertEquals(email, r("emails"))
        assertEquals(email, r("who emailed me today"))
        assertEquals(email, r("did anyone email me"))
        assertEquals(email, r("did I get any e-mails"))
        assertEquals(Request.ReadNotifications(true, "John"), r("did John email me"))
        assertEquals(Request.ReadNotifications(true, "my boss"), r("has my boss emailed me today"))
        assertEquals(Request.ReadNotifications(true, "Amazon"), r("any emails from Amazon today"))
        assertEquals(notes, r("do I have any notifications"))
        assertEquals(notes, r("what notifications do I have"))
        assertEquals(notes, r("any new notifs"))
        assertEquals(notes, r("notifications please"))
        assertEquals(notes, r("catch me up"))
        assertEquals(notes, r("anything new on my phone"))
        assertEquals(notes, r("did I miss anything"))
        assertEquals(Request.ReadNotifications(false, "instagram"), r("any instagram notifications"))
        assertEquals(Request.ReadNotifications(false, "Discord"), r("read my notifications from Discord"))
        // Not reading.
        for (t in listOf("remind me to check my email tomorrow", "turn off notifications", "send an email to John",
            "open gmail", "what's on my calendar")) {
            assertTrue(t, r(t) !is Request.ReadNotifications)
        }
    }

    @Test
    fun parsesYouTube() {
        assertEquals(Request.YouTube("lofi hip hop", play = true, music = false), IntentParser.parse("play lofi hip hop on YouTube"))
        assertEquals(Request.YouTube("The Prince by Madeon", play = true, music = true), IntentParser.parse("play The Prince by Madeon on YouTube Music"))
        assertEquals(Request.YouTube("how to fix a bike chain", play = false, music = false), IntentParser.parse("search YouTube for how to fix a bike chain"))
        assertEquals(Request.YouTube("cats", play = false, music = false), IntentParser.parse("find videos of cats"))
        // Other apps unaffected
        assertTrue(IntentParser.parse("play The Prince by Madeon on Spotify") is Request.Music)
    }

    @Test
    fun speaksNotifications() {
        val items = listOf(
            NoticeItem("Messages", "Mom", "I'm on my way", 3, false),
            NoticeItem("Gmail", "John Smith", "Lunch tomorrow?", 2, true),
        )
        assertEquals("You have 2 notifications. Messages, Mom: I'm on my way. Gmail, John Smith: Lunch tomorrow?.", NotificationReader.speak(items, false, null))
        assertEquals("You have one email. From John Smith: Lunch tomorrow?.", NotificationReader.speak(items.filter { it.email }, true, null))
        assertEquals("I don't see any emails from Alex.", NotificationReader.speak(items.filter { it.email }, true, "Alex"))
    }

    @Test
    fun speaksCalendar() {
        val today = LocalDate.of(2026, 11, 9)
        fun at(h: Int, m: Int = 0) = today.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val evs = listOf(CalEvent("Dentist", at(15), at(16), false, null), CalEvent("Gym", at(18, 30), at(19, 30), false, "Planet Fitness"))
        assertEquals("You have 2 events today: Dentist at 3 PM, and Gym at 6:30 PM.", CalendarReader.speak(evs, CalendarRange.TODAY, today))
        assertEquals("Your next event is Dentist today at 3 PM.", CalendarReader.speak(evs.take(1), CalendarRange.NEXT, today))
        assertEquals("Your calendar is clear tomorrow.", CalendarReader.speak(emptyList(), CalendarRange.TOMORROW, today))
    }

    @Test
    fun findsYouTubeVideoId() {
        val html = "...\"adSlotRenderer\":{}...\"videoRenderer\":{\"videoId\":\"dQw4w9WgXcQ\",\"thumbnail\":{}}"
        assertEquals("dQw4w9WgXcQ", YouTubeSearch.firstIdIn(html))
    }
}
