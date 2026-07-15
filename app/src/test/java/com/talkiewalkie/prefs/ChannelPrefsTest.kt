package com.talkiewalkie.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.talkiewalkie.model.Role
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChannelPrefsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clearPrefs() = ChannelPrefs.clear(context)

    // ── round-trips ───────────────────────────────────────────────────────────

    @Test fun hubRoundTrip() {
        ChannelPrefs.save(context, "riders", Role.HUB)
        val (name, role) = ChannelPrefs.load(context)!!
        assertEquals("riders", name)
        assertEquals(Role.HUB, role)
    }

    @Test fun clientRoundTrip() {
        ChannelPrefs.save(context, "team-alpha", Role.CLIENT)
        val (name, role) = ChannelPrefs.load(context)!!
        assertEquals("team-alpha", name)
        assertEquals(Role.CLIENT, role)
    }

    @Test fun channelNameWithSpacesRoundTrip() {
        ChannelPrefs.save(context, "my channel", Role.HUB)
        val (name, _) = ChannelPrefs.load(context)!!
        assertEquals("my channel", name)
    }

    // ── empty / cleared state ─────────────────────────────────────────────────

    @Test fun loadReturnsNullWhenNothingSaved() {
        assertNull(ChannelPrefs.load(context))
    }

    @Test fun clearMakesSubsequentLoadReturnNull() {
        ChannelPrefs.save(context, "riders", Role.HUB)
        ChannelPrefs.clear(context)
        assertNull(ChannelPrefs.load(context))
    }

    @Test fun saveOverwritesPreviousEntry() {
        ChannelPrefs.save(context, "old-channel", Role.HUB)
        ChannelPrefs.save(context, "new-channel", Role.CLIENT)
        val (name, role) = ChannelPrefs.load(context)!!
        assertEquals("new-channel", name)
        assertEquals(Role.CLIENT, role)
    }

    // ── Role.NONE filtering ───────────────────────────────────────────────────

    @Test fun roleNoneIsFilteredOnLoad() {
        // save() accepts any Role so we can deliberately store NONE and verify
        // that load() rejects it — guarding against future callers passing NONE.
        ChannelPrefs.save(context, "riders", Role.NONE)
        assertNull(ChannelPrefs.load(context))
    }

    // ── corrupt prefs defence ─────────────────────────────────────────────────

    @Test fun invalidRoleStringIsFilteredOnLoad() {
        // Simulate a prefs file written by a future or past app version that
        // stored an unrecognised role value. The role key names are an
        // implementation detail; hardcoding them here is intentional so the
        // test breaks loudly if the keys change without updating this guard.
        context.getSharedPreferences("channel_prefs", Context.MODE_PRIVATE).edit()
            .putString("last_channel_name", "riders")
            .putString("last_channel_role", "SUPERUSER")
            .commit()
        assertNull(ChannelPrefs.load(context))
    }

    @Test fun missingRoleKeyIsFilteredOnLoad() {
        context.getSharedPreferences("channel_prefs", Context.MODE_PRIVATE).edit()
            .putString("last_channel_name", "riders")
            // last_channel_role intentionally absent
            .commit()
        assertNull(ChannelPrefs.load(context))
    }

    @Test fun missingNameKeyIsFilteredOnLoad() {
        context.getSharedPreferences("channel_prefs", Context.MODE_PRIVATE).edit()
            .putString("last_channel_role", "HUB")
            // last_channel_name intentionally absent
            .commit()
        assertNull(ChannelPrefs.load(context))
    }
}
