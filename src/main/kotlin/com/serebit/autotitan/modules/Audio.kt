package com.serebit.autotitan.modules

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.serebit.autotitan.api.Module
import com.serebit.autotitan.api.meta.Locale
import com.serebit.autotitan.api.meta.annotations.Command
import com.serebit.autotitan.api.meta.annotations.Listener
import com.serebit.extensions.jda.sendEmbed
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.audio.AudioSendHandler
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.MessageChannel
import net.dv8tion.jda.core.entities.VoiceChannel
import net.dv8tion.jda.core.events.guild.voice.GuildVoiceLeaveEvent
import net.dv8tion.jda.core.events.guild.voice.GuildVoiceMoveEvent
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.managers.AudioManager
import org.apache.commons.validator.routines.UrlValidator

class Audio : Module() {
    private val urlValidator = UrlValidator(arrayOf("http", "https"))
    private val playerManager = DefaultAudioPlayerManager()
    private val musicManagers = mutableMapOf<Long, GuildMusicManager>()
    private val Guild.musicManager: GuildMusicManager
        get() {
            val musicManager = musicManagers.getOrElse(idLong, {
                val newManager = GuildMusicManager(playerManager)
                musicManagers[idLong] = newManager
                newManager
            })
            audioManager.sendingHandler = musicManager.sendHandler
            return musicManager
        }

    init {
        AudioSourceManagers.registerRemoteSources(playerManager)
        AudioSourceManagers.registerLocalSource(playerManager)
    }

    @Command(description = "Joins the voice channel that the invoker is in.", locale = Locale.GUILD)
    fun joinVoice(evt: MessageReceivedEvent) {
        evt.run {
            voiceStatus(evt).let {
                when(it) {
                    VoiceStatus.USER_NOT_CONNECTED -> it.sendErrorMessage(evt.channel)
                    VoiceStatus.CONNECTED_DIFFERENT_CHANNEL, VoiceStatus.CONNECTED_SAME_CHANNEL -> {
                        channel.sendMessage("I'm already in a voice channel.").complete()
                    }
                    VoiceStatus.SELF_NOT_CONNECTED -> {
                        connectToVoiceChannel(guild.audioManager, member.voiceState.channel)
                        channel.sendMessage("Now connected to ${member.voiceState.channel.name}.").complete()
                    }
                }
            }
        }
    }

    @Command(description = "Leaves the voice channel that the bot is in.", locale = Locale.GUILD)
    fun leaveVoice(evt: MessageReceivedEvent) = leaveVoiceChannel(evt.guild)

    @Command(
        description = "Plays a URL, or searches YouTube for the given search terms.",
        locale = Locale.GUILD,
        splitLastParameter = false
    )
    fun play(evt: MessageReceivedEvent, query: String) {
        evt.run {
            val voiceStatus = voiceStatus(evt)
            when (voiceStatus) {
                VoiceStatus.CONNECTED_DIFFERENT_CHANNEL, VoiceStatus.USER_NOT_CONNECTED -> {
                    voiceStatus.sendErrorMessage(channel)
                }
                VoiceStatus.SELF_NOT_CONNECTED -> connectToVoiceChannel(guild.audioManager, member.voiceState.channel)
                VoiceStatus.CONNECTED_SAME_CHANNEL -> {
                    val audioManager = guild.musicManager
                    val formattedQuery = if (urlValidator.isValid(query)) {
                        query
                    } else {
                        "ytsearch:$query"
                    }
                    playerManager.loadItemOrdered(audioManager, formattedQuery, object : AudioLoadResultHandler {
                        override fun trackLoaded(track: AudioTrack) {
                            channel.sendMessage("Adding ${track.info.title} to queue.").complete()
                            audioManager.scheduler.addToQueue(track)
                        }

                        override fun playlistLoaded(playlist: AudioPlaylist) {
                            if (playlist.isSearchResult) {
                                val track = playlist.tracks[0]
                                audioManager.scheduler.addToQueue(track)
                                channel.sendMessage("Adding ${track.info.title} to queue.").complete()
                            } else {
                                channel.sendMessage("Adding ${playlist.tracks.size} songs from ${playlist.name} to queue.")
                                    .complete()
                                playlist.tracks.forEach { audioManager.scheduler.addToQueue(it) }
                            }
                        }

                        override fun noMatches() {
                            channel.sendMessage("Nothing found.").complete()
                        }

                        override fun loadFailed(exception: FriendlyException) {
                            channel.sendMessage("Could not queue: ${exception.message}").complete()
                        }
                    })
                }
            }
        }
    }

    @Command(description = "Skips the currently playing song.", locale = Locale.GUILD)
    fun skip(evt: MessageReceivedEvent) {
        evt.run {
            voiceStatus(evt).let {
                if (it != VoiceStatus.CONNECTED_SAME_CHANNEL) {
                    it.sendErrorMessage(channel)
                    return
                }
            }
            val audioManager = guild.musicManager
            if (audioManager.scheduler.queue.isEmpty() && audioManager.player.playingTrack == null) {
                channel.sendMessage("Cannot skip. Nothing is playing.").complete()
                return
            }
            audioManager.scheduler.skipTrack()
            channel.sendMessage("Skipped to next track.").complete()
        }
    }

    @Command(
        description = "Stops playing music and clears the queue.",
        locale = Locale.GUILD,
        memberPermissions = [Permission.VOICE_MUTE_OTHERS]
    )
    fun stop(evt: MessageReceivedEvent) {
        evt.run {
            guild.musicManager.scheduler.stop()
            channel.sendMessage("Cleared the music queue.").complete()
        }
    }

    @Command(description = "Pauses the currently playing song.", locale = Locale.GUILD)
    fun pause(evt: MessageReceivedEvent) {
        evt.run {
            voiceStatus(evt).let {
                if (it != VoiceStatus.CONNECTED_SAME_CHANNEL) {
                    it.sendErrorMessage(channel)
                    return
                }
            }
            val audioManager = guild.musicManager
            if (audioManager.scheduler.pause()) {
                channel.sendMessage("Paused.").complete()
            }
        }
    }

    @Command(description = "Resumes the currently playing song.", locale = Locale.GUILD)
    fun resume(evt: MessageReceivedEvent) {
        evt.run {
            voiceStatus(evt).let {
                if (it != VoiceStatus.CONNECTED_SAME_CHANNEL) {
                    it.sendErrorMessage(channel)
                    return
                }
            }
            if (guild.musicManager.scheduler.resume()) {
                channel.sendMessage("Resumed.").complete()
            }
        }
    }

    @Command(description = "Sends an embed with the list of songs in the queue.", locale = Locale.GUILD)
    fun queue(evt: MessageReceivedEvent) {
        evt.run {
            if (guild.musicManager.player.playingTrack == null) {
                channel.sendMessage("No songs are queued.").complete()
                return
            }
            val audioManager = guild.musicManager

            channel.sendEmbed {
                setAuthor(guild.selfMember.effectiveName, null, jda.selfUser.effectiveAvatarUrl)
                setTitle("Music Queue", null)
                val playingTrack = audioManager.player.playingTrack
                val position = toHumanReadableDuration(playingTrack.position)
                val duration = toHumanReadableDuration(playingTrack.duration)
                val upNextList = audioManager.scheduler.queue.take(8).joinToString("\n") {
                    "${it.info.title} (${toHumanReadableDuration(it.duration)})"
                }
                addField(
                    "Now Playing",
                    "${playingTrack.info.title} ($position/$duration)",
                    false
                )
                if (audioManager.scheduler.queue.isNotEmpty()) addField(
                    "Up Next",
                    upNextList + if (audioManager.scheduler.queue.size > 8) {
                        "\n plus ${audioManager.scheduler.queue.drop(8).size} more..."
                    } else "",
                    false
                )
            }.complete()
        }
    }

    @Command(description = "Sets the volume.", locale = Locale.GUILD)
    fun setVolume(evt: MessageReceivedEvent, volume: Int) {
        evt.run {
            voiceStatus(evt).let {
                if (it != VoiceStatus.CONNECTED_SAME_CHANNEL) {
                    it.sendErrorMessage(channel)
                    return
                }
            }
            guild.musicManager.player.volume = volume.coerceIn(0..100)
            channel.sendMessage("Set volume to ${guild.musicManager.player.volume}%.").complete()
        }
    }

    @Listener
    fun leaveVoiceAutomatically(evt: GuildVoiceLeaveEvent) {
        evt.run {
            if (guild.audioManager.connectedChannel != channelLeft) return
            if (guild.audioManager.connectedChannel.members.any { !it.user.isBot }) return
            leaveVoiceChannel(guild)
        }
    }

    @Listener
    fun leaveVoiceAutomatically(evt: GuildVoiceMoveEvent) {
        evt.run {
            if (guild.audioManager.connectedChannel != channelLeft) return
            if (guild.audioManager.connectedChannel.members.any { !it.user.isBot }) return
            leaveVoiceChannel(guild)
        }
    }

    private fun leaveVoiceChannel(guild: Guild) {
        if (guild.audioManager.isConnected) {
            guild.musicManager.scheduler.resume()
            guild.musicManager.scheduler.stop()
            guild.musicManager.player.volume = 100
            guild.audioManager.closeAudioConnection()
        }
    }

    private fun connectToVoiceChannel(audioManager: AudioManager, voiceChannel: VoiceChannel) {
        if (!audioManager.isConnected && !audioManager.isAttemptingToConnect) {
            audioManager.openAudioConnection(voiceChannel)
        }
    }

    private fun voiceStatus(evt: MessageReceivedEvent): VoiceStatus {
        evt.run {
            val selfIsConnected = guild.audioManager.isConnected
            val userIsConnected = member.voiceState.inVoiceChannel()
            val differentChannel =
                userIsConnected && selfIsConnected && member.voiceState.channel != guild.audioManager.connectedChannel
            return when {
                !userIsConnected -> VoiceStatus.USER_NOT_CONNECTED
                !selfIsConnected -> VoiceStatus.SELF_NOT_CONNECTED
                differentChannel -> VoiceStatus.CONNECTED_DIFFERENT_CHANNEL
                else -> VoiceStatus.CONNECTED_SAME_CHANNEL
            }
        }
    }

    private enum class VoiceStatus(val errorMessage: String?) {
        SELF_NOT_CONNECTED("I need to be in a voice channel to do that."),
        USER_NOT_CONNECTED("You need to be in a voice channel for me to do that."),
        CONNECTED_DIFFERENT_CHANNEL("We need to be in the same voice channel for you to do that."),
        CONNECTED_SAME_CHANNEL(null);

        fun sendErrorMessage(channel: MessageChannel) {
            errorMessage?.let { channel.sendMessage(it).complete() }
        }
    }

    private class GuildMusicManager(manager: AudioPlayerManager) {
        val player: AudioPlayer = manager.createPlayer()
        val scheduler = TrackScheduler()
        val sendHandler by lazy {
            AudioPlayerSendHandler()
        }

        init {
            player.addListener(scheduler)
        }

        inner class TrackScheduler : AudioEventAdapter() {

            val queue = mutableListOf<AudioTrack>()
            fun addToQueue(track: AudioTrack) {
                if (player.playingTrack == null) {
                    player.playTrack(track)
                } else {
                    queue.add(track)
                }
            }

            fun skipTrack() {
                if (player.playingTrack != null) {
                    player.stopTrack()
                    if (queue.isNotEmpty()) player.playTrack(queue.removeAt(0))
                }
            }

            fun pause() = if (!player.isPaused) {
                player.isPaused = true
                true
            } else false

            fun resume() = if (player.isPaused) {
                player.isPaused = false
                true
            } else false

            fun stop() {
                player.stopTrack()
                queue.clear()
            }

            override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
                if (queue.isNotEmpty() && endReason == AudioTrackEndReason.FINISHED) {
                    player.playTrack(queue.removeAt(0))
                }
            }

        }

        inner class AudioPlayerSendHandler : AudioSendHandler {
            private var lastFrame: AudioFrame? = null
            override fun canProvide(): Boolean {
                if (lastFrame == null) lastFrame = player.provide()
                return lastFrame != null
            }

            override fun provide20MsAudio(): ByteArray? {
                if (lastFrame == null) lastFrame = player.provide()
                val data = if (lastFrame != null) lastFrame?.data else null
                lastFrame = null
                return data
            }

            override fun isOpus() = true

        }
    }

    private fun toHumanReadableDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = (totalSeconds / 3600).toInt()
        val minutes = (totalSeconds % 3600 / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
        return when (hours) {
            0 -> String.format("%d:%02d", minutes, seconds)
            else -> String.format("%d:%02d:%02d", hours, minutes, seconds)
        }
    }
}
