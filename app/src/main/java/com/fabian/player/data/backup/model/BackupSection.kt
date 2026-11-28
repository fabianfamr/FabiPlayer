package com.fabian.player.data.backup.model

import androidx.annotation.StringRes
import com.fabian.player.R

enum class BackupSection(
    val key: String,
    val label: String,
    val description: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val iconRes: Int,
    val sinceVersion: Int = 1
) {
    PLAYLISTS(
        key = "playlists",
        label = "Playlists",
        description = "Your custom playlists and ordering preferences.",
        labelRes = R.string.backup_section_playlists_label,
        descriptionRes = R.string.backup_section_playlists_desc,
        iconRes = R.drawable.rounded_playlist_play_24
    ),
    GLOBAL_SETTINGS(
        key = "global_settings",
        label = "Global Settings",
        description = "Themes, behavior, playback, and app preferences.",
        labelRes = R.string.backup_section_global_settings_label,
        descriptionRes = R.string.backup_section_global_settings_desc,
        iconRes = R.drawable.rounded_settings_24
    ),
    FAVORITES(
        key = "favorites",
        label = "Favorites",
        description = "Songs marked as favorite.",
        labelRes = R.string.backup_section_favorites_label,
        descriptionRes = R.string.backup_section_favorites_desc,
        iconRes = R.drawable.rounded_favorite_24
    ),
    LYRICS(
        key = "lyrics",
        label = "Saved Lyrics",
        description = "Lyrics you've saved or imported.",
        labelRes = R.string.backup_section_lyrics_label,
        descriptionRes = R.string.backup_section_lyrics_desc,
        iconRes = R.drawable.rounded_lyrics_24
    ),
    SEARCH_HISTORY(
        key = "search_history",
        label = "Search History",
        description = "Recent search terms in the app.",
        labelRes = R.string.backup_section_search_history_label,
        descriptionRes = R.string.backup_section_search_history_desc,
        iconRes = R.drawable.rounded_search_24
    ),
    TRANSITIONS(
        key = "transitions",
        label = "Transition Rules",
        description = "Custom transition settings between songs.",
        labelRes = R.string.backup_section_transitions_label,
        descriptionRes = R.string.backup_section_transitions_desc,
        iconRes = R.drawable.rounded_align_justify_space_even_24
    ),
    ENGAGEMENT_STATS(
        key = "engagement_stats",
        label = "Engagement Stats",
        description = "Play count and listening duration per song.",
        labelRes = R.string.backup_section_engagement_stats_label,
        descriptionRes = R.string.backup_section_engagement_stats_desc,
        iconRes = R.drawable.rounded_monitoring_24
    ),
    PLAYBACK_HISTORY(
        key = "playback_history",
        label = "Playback History",
        description = "Timeline-based listening history for stats.",
        labelRes = R.string.backup_section_playback_history_label,
        descriptionRes = R.string.backup_section_playback_history_desc,
        iconRes = R.drawable.rounded_schedule_24
    ),
    QUICK_FILL(
        key = "quick_fill",
        label = "QuickFill Genres",
        description = "Custom genres and their icons.",
        labelRes = R.string.backup_section_quick_fill_label,
        descriptionRes = R.string.backup_section_quick_fill_desc,
        iconRes = R.drawable.rounded_instant_mix_24,
        sinceVersion = 3
    ),
    ARTIST_IMAGES(
        key = "artist_images",
        label = "Artist Images",
        description = "Cached artist image URLs from Deezer.",
        labelRes = R.string.backup_section_artist_images_label,
        descriptionRes = R.string.backup_section_artist_images_desc,
        iconRes = R.drawable.rounded_person_24,
        sinceVersion = 3
    ),
    EQUALIZER(
        key = "equalizer",
        label = "Equalizer",
        description = "Your custom equalizer presets and audio profiles.",
        labelRes = R.string.backup_section_equalizer_label,
        descriptionRes = R.string.backup_section_equalizer_desc,
        iconRes = R.drawable.rounded_surround_sound_24,
        sinceVersion = 3
    ),
    AUDIO_BOOKMARKS(
        key = "audio_bookmarks",
        label = "Audio Bookmarks",
        description = "Saved timestamps inside songs and audiobooks.",
        labelRes = R.string.backup_section_audio_bookmarks_label,
        descriptionRes = R.string.backup_section_audio_bookmarks_desc,
        iconRes = R.drawable.round_bookmark_24,
        sinceVersion = 4
    );

    companion object {
        val defaultSelection: Set<BackupSection> = entries.toSet()

        fun fromKey(key: String): BackupSection? = entries.find { it.key == key }
    }
}
