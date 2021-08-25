/*
 *  * Copyright (c)  2021  Shabinder Singh
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  *  You should have received a copy of the GNU General Public License
 *  *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.shabinder.common.providers

import co.touchlab.kermit.Kermit
import com.shabinder.common.core_components.file_manager.FileManager
import com.shabinder.common.core_components.preference_manager.PreferenceManager
import com.shabinder.common.database.DownloadRecordDatabaseQueries
import com.shabinder.common.models.*
import com.shabinder.common.models.event.coroutines.SuspendableEvent
import com.shabinder.common.models.event.coroutines.flatMapError
import com.shabinder.common.models.event.coroutines.success
import com.shabinder.common.models.spotify.Source
import com.shabinder.common.providers.gaana.GaanaProvider
import com.shabinder.common.providers.saavn.SaavnProvider
import com.shabinder.common.providers.spotify.SpotifyProvider
import com.shabinder.common.providers.youtube.YoutubeProvider
import com.shabinder.common.providers.youtube.get
import com.shabinder.common.providers.youtube_music.YoutubeMusic
import com.shabinder.common.providers.youtube_to_mp3.requests.YoutubeMp3
import com.shabinder.common.utils.appendPadded
import com.shabinder.common.utils.buildString
import com.shabinder.common.utils.requireNotNull
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class FetchPlatformQueryResult(
    private val gaanaProvider: GaanaProvider,
    private val spotifyProvider: SpotifyProvider,
    private val youtubeProvider: YoutubeProvider,
    private val saavnProvider: SaavnProvider,
    private val youtubeMusic: YoutubeMusic,
    private val youtubeMp3: YoutubeMp3,
    val fileManager: FileManager,
    val preferenceManager: PreferenceManager,
    val logger: Kermit
) {
    private val db: DownloadRecordDatabaseQueries?
        get() = fileManager.db?.downloadRecordDatabaseQueries

    suspend fun authenticateSpotifyClient() = spotifyProvider.authenticateSpotifyClient()

    suspend fun query(link: String): SuspendableEvent<PlatformQueryResult, Throwable> {
        val result = when {
            // SPOTIFY
            link.contains("spotify", true) ->
                spotifyProvider.query(link)

            // YOUTUBE
            link.contains("youtube.com", true) || link.contains("youtu.be", true) ->
                youtubeProvider.query(link)

            // Jio Saavn
            link.contains("saavn", true) ->
                saavnProvider.query(link)

            // GAANA
            link.contains("gaana", true) ->
                gaanaProvider.query(link)

            else -> {
                SuspendableEvent.error(SpotiFlyerException.LinkInvalid(link))
            }
        }
        result.success {
            addToDatabaseAsync(
                link,
                it.copy() // Send a copy in order to not freeze Result itself
            )
        }
        return result
    }

    // 1) Try Finding on JioSaavn (better quality upto 320KBPS)
    // 2) If Not found try finding on YouTube Music
    suspend fun findBestDownloadLink(
        track: TrackDetails,
        preferredQuality: AudioQuality = preferenceManager.audioQuality
    ): SuspendableEvent<String, Throwable> {
        var downloadLink: String? = null

        val errorTrace = buildString(track) {
            if (track.videoID != null) {
                // We Already have VideoID
                downloadLink = when (track.source) {
                    Source.JioSaavn -> {
                        saavnProvider.getSongFromID(track.videoID.requireNotNull()).component1()?.media_url
                    }
                    Source.YouTube -> {
                        youtubeMp3.getMp3DownloadLink(track.videoID.requireNotNull(), preferredQuality)
                            .let { ytMp3Link ->
                                if (ytMp3Link is SuspendableEvent.Failure || ytMp3Link.component1().isNullOrBlank()) {
                                    appendPadded(
                                        "Yt1sMp3 Failed for ${track.videoID}:",
                                        ytMp3Link.component2()
                                            ?: "couldn't fetch link for ${track.videoID} ,trying manual extraction"
                                    )
                                    appendLine("Trying Local Extraction")
                                    youtubeProvider.ytDownloader.getVideo(track.videoID!!).get()?.url
                                } else ytMp3Link.component1()
                            }
                    }
                    else -> {
                        appendPadded(
                            "Invalid Arguments",
                            "VideoID with ${track.source} source is not defined how to be handled"
                        )
                        /*We should never reach here for now*/
                        null
                    }
                }
            }
            // if videoID wasn't present || fetching using video ID failed
            if (downloadLink.isNullOrBlank()) {

                // Try Fetching Track from Available Sources
                val queryResult = saavnProvider.findBestSongDownloadURL(
                    trackName = track.title,
                    trackArtists = track.artists,
                    preferredQuality = preferredQuality
                ).flatMapError { saavnError ->
                    appendPadded("Fetching From Saavn Failed:", saavnError.stackTraceToString())
                    // Saavn Failed, Lets Try Fetching Now From Youtube Music
                    youtubeMusic.findMp3SongDownloadURLYT(track, preferredQuality).also {
                        if (it is SuspendableEvent.Failure)
                            appendPadded("Fetching From YT-Music Failed:", it.component2()?.stackTraceToString())
                    }
                }

                downloadLink = queryResult.component1()
            }
        }
        return if (downloadLink.isNullOrBlank()) SuspendableEvent.error(
            SpotiFlyerException.DownloadLinkFetchFailed(errorTrace)
        ) else SuspendableEvent.success(downloadLink.requireNotNull())
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun addToDatabaseAsync(link: String, result: PlatformQueryResult) {
        GlobalScope.launch(dispatcherIO) {
            db?.add(
                result.folderType, result.title, link, result.coverUrl, result.trackList.size.toLong()
            )
        }
    }
}
