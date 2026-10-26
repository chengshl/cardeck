package io.github.shaonialife.cardeck.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelTest {
    @Test
    fun testChannelHasItsOwnReleaseAndCache() {
        val channel = UpdateChannel.create("chengshl/cardeck", "Buick-Test", "buick-test-latest", "CarDeck-Buick-Test.apk")
        assertEquals("https://api.github.com/repos/chengshl/cardeck/releases/tags/buick-test-latest", channel.releaseUrl)
        assertEquals("updates/buick-test-latest/CarDeck-Buick-Test.apk", channel.cachePath)
        assertTrue(channel.acceptsSource("buick-test-latest", "Buick-Test"))
        assertFalse(channel.acceptsSource("cardeck-latest", "Buick-Test"))
        assertFalse(channel.acceptsSource("buick-test-latest", "Buick-E5"))
        assertFalse(channel.acceptsSource("buick-test-latest", "Buick-main"))
    }

    @Test
    fun officialBuickChannelRejectsOtherReleaseTracks() {
        val channel = UpdateChannel.create("chengshl/cardeck", "Buick", "buick-latest", "CarDeck-Buick.apk")
        assertTrue(channel.acceptsSource("buick-latest", "Buick"))
        assertFalse(channel.acceptsSource("buick-main-latest", "Buick-main"))
        assertFalse(channel.acceptsSource("buick-test-latest", "Buick-Test"))
    }

    @Test
    fun rejectsPathsThatCouldEscapeCacheOrInjectApiUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateChannel.create("chengshl/cardeck", "Buick-Test", "buick-test-latest", "../../shared.apk")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateChannel.create("https://invalid.example/repo", "Buick-Test", "test", "test.apk")
        }
    }
}
