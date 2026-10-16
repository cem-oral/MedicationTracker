package com.ilactakipmobil.app.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File

object AlarmSoundManager {

    private var ringtone: Ringtone? = null
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable { stop() }

    /**
     * Alarm sesini çalmaya başlar. 20 saniye sonra otomatik susacak şekilde zamanlanır.
     */
    fun play(context: Context, soundUri: Uri) {
        stop() // Varsa öncekini durdur

        try {
            val prefs = context.getSharedPreferences("ilac_takip_prefs", Context.MODE_PRIVATE)
            val useCustom = prefs.getBoolean("key_use_custom_sound", false)
            val customFile = File(context.filesDir, "custom_alarm_sound.3gp")

            if (useCustom && customFile.exists()) {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(customFile.absolutePath)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    prepare()
                    start()
                }
            } else {
                ringtone = RingtoneManager.getRingtone(context.applicationContext, soundUri)
                ringtone?.play()
            }

            // 20 saniye sonra çalmayı otomatik durdur (sonsuza kadar çalmaması için)
            handler.removeCallbacks(autoStopRunnable)
            handler.postDelayed(autoStopRunnable, 20_000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Çalan alarm sesini durdurur.
     */
    fun stop() {
        handler.removeCallbacks(autoStopRunnable)
        try {
            ringtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            ringtone = null
        }

        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaPlayer = null
        }
    }
}
