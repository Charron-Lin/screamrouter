package nodomain.scream.screamfromandroid
// Scream 协议头长度：HEADER_SIZE = 5。
// 完整数据包长度：PACKET_SIZE = 1152 + HEADER_SIZE。

import android.Manifest
import android.R
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 采集设备音频并发送到 Scream 接收端的后台服务。
 * Scream 协议：https://github.com/duncanthrax/scream
 */
class ScreamAudioService : Service() {

    companion object {
        private const val TAG = "ScreamAudioService"
        private const val CHANNEL_ID = "ScreamAudioChannel"
        private const val NOTIFICATION_ID = 1

        // Scream 协议的默认音频格式与网络配置。
        private const val SAMPLE_RATE = 44100
        private const val MULTIPLIER = 1
        private const val BITS_PER_SAMPLE = 16
        const val SCREAM_IP = "239.255.77.77" // 默认的 Scream 组播 IP 地址。
        const val SCREAM_PORT = 4010 // 默认的 Scream UDP 端口。

        private const val PACKET_SIZE = 1152  // 标准音频负载为 1152 字节，不含 5 字节协议头；上游观察到较小的数据包有助于减少卡顿。
    }

    private var audioRecord: AudioRecord? = null
    private var socket: DatagramSocket? = null
    private var screamServerAddress: InetAddress? = null
    private var screamServerPort = SCREAM_PORT

    @Volatile
    private var isCapturing = false
    private var captureThread: Thread? = null

    private var mediaProjection: MediaProjection? = null
    private var resultCode: Int = -1
    private var resultData: Intent? = null

    private var audioManager: AudioManager? = null

    private lateinit var mediaSession: MediaSessionCompat
    private var originalVolume: Int? = null
    private var screamVolume: Int = 0x10000 // 使用整数定点运算调整音量，以减少浮点运算开销。

    /** 创建服务并注册前台推流通知渠道。 */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /** 根据 intent 启停采集，立即发布前台通知；保留 flags、startId 对应的系统重启语义并返回 START_STICKY。 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立即进入前台服务状态，避免系统等待超时。
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        intent?.let {
            when (it.action) {
                "START_CAPTURE" -> {
                    val serverIp = it.getStringExtra("SERVER_IP") ?: SCREAM_IP
                    screamServerPort = it.getIntExtra("SERVER_PORT", SCREAM_PORT)
                    resultCode = it.getIntExtra("RESULT_CODE", 0)
                    resultData = it.getParcelableExtra("RESULT_DATA")

                    Log.d(TAG, "onStartCommand - serverIp: $serverIp, port: $screamServerPort, resultCode: $resultCode, hasData: ${resultData != null}")

                    if (resultCode == Activity.RESULT_OK && resultData != null) {
                        if (ActivityCompat.checkSelfPermission(
                                this,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            startCapture(serverIp)
                        } else {
                            Toast.makeText(this, "Grant capture permission so audio can be captured", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.e(TAG, "Missing required parameters - IP: $serverIp, code: $resultCode, data: $resultData")
                        stopSelf()
                    }
                }
                "STOP_CAPTURE" -> stopCapture()
            }
        }

        return START_STICKY
    }

    /** 从 prefs 恢复推流音量并建立媒体会话；兼容上游早期版本保存的浮点音量。 */
    private fun setupVolumeMixer(prefs: SharedPreferences) {
        // 为网络推流创建独立的系统音量控制入口。
        mediaSession = MediaSessionCompat(this, "ScreamService")
        mediaSession.isActive = true
        try {
            screamVolume = prefs.getInt("SCREAM_VOLUME", screamVolume)
        } catch(e: ClassCastException) {
            Log.i(TAG, "Restoring stored volume setting from v1.1 and earlier")
            screamVolume = (prefs.getFloat("SCREAM_VOLUME",screamVolume.toFloat()/0x10000) * 0x10000).toInt()
        }

        mediaSession.setPlaybackToRemote(object : VolumeProviderCompat(
            VOLUME_CONTROL_ABSOLUTE, 10, (screamVolume * 10).toInt()
        ) {
            /** 将 volume 映射为定点音量并保存，同时同步系统音量条。 */
            override fun onSetVolumeTo(volume: Int) {
                screamVolume = (volume * 0x10000 / 10).coerceIn(0, 0x10000)
                Log.d(TAG, "SetVolumeTo volume=$volume, screamVolume=$screamVolume")
                setCurrentVolume((screamVolume.toFloat() * 10 / 0x10000).roundToInt())  // 同步系统显示的音量，避免音量条跳回 100%。
                prefs.edit().putInt("SCREAM_VOLUME",  screamVolume).apply()
            }

            /** 根据 direction 调整推流音量，限制在系统音量条支持的范围内。 */
            override fun onAdjustVolume(direction: Int) {
                Log.d(TAG, "onAdjustVolume volume=$direction, screamVolume=$screamVolume")
                this.onSetVolumeTo (((screamVolume.toFloat() * 10 / 0x10000).roundToInt() + direction).coerceIn(0, 10) )
                super.onAdjustVolume(direction)
            }
        })

        // 将播放状态设为活动，使系统启用推流音量控制。
        val playbackState = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    /** 连接 serverIp 并启动音频采集线程；按偏好静音本机播放，异常时释放资源并停止服务。 */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startCapture(serverIp: String) {
        val prefs = this.applicationContext.getSharedPreferences("user_prefs", MODE_PRIVATE)
        val channels = prefs.getInt("SCREAM_CHANNELS", 2)
        setupVolumeMixer(prefs)

        if (isCapturing) {
            Log.w(TAG, "Already capturing")
            return
        }

        try {
            screamServerAddress = InetAddress.getByName(serverIp)
            socket = DatagramSocket()
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData!!)

            var bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                when (channels) {
                    1 -> AudioFormat.CHANNEL_IN_MONO
                    else ->AudioFormat.CHANNEL_IN_STEREO
                },
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2 // 将最小缓冲区加倍，降低采集时缓冲不足的风险。
            bufferSize = max(bufferSize, PACKET_SIZE * 2)

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(when (channels) {
                    1 -> AudioFormat.CHANNEL_IN_MONO
                    else ->AudioFormat.CHANNEL_IN_STEREO
                })
                .build()

            // Android 10 及以上版本使用播放捕获接口采集设备内部音频。
            audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 创建播放捕获配置，仅匹配媒体、游戏和未分类音频。
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } else {
                // 较旧版本回退为麦克风采集。
                AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    SAMPLE_RATE,
                    when (channels) {
                        1 -> AudioFormat.CHANNEL_IN_MONO
                        else ->AudioFormat.CHANNEL_IN_STEREO
                    },
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }

            isCapturing = true
            captureThread = Thread { captureAndStream(channels) }.apply { start() }

            audioRecord?.startRecording()

            val state = audioRecord?.state
            val recordingState = audioRecord?.recordingState

            Log.i(TAG, "AudioRecord initialized - State: $state, RecordingState: $recordingState")
            Log.i(TAG, "Audio capture started, streaming to $serverIp:$screamServerPort")

            val mute = prefs.getBoolean("SCREAM_MUTE", true)
            if (mute) {
                audioManager = getSystemService(AUDIO_SERVICE) as AudioManager  // 获取设备当前媒体音量，便于停止后恢复。
                originalVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture", e)
            cleanup()
            stopSelf()
        }
    }

    /** 对 buffer 中 bytesRead 字节的 16 位小端 PCM 按定点 volume 原地调节音量，返回峰值是否不超过 silenceThreshold。 */
    private fun isSilentAndChangeVolume(buffer: ByteArray, bytesRead: Int, volume: Int, silenceThreshold: Int = 200): Boolean {
        var maxAmplitude = 0
        // 以两个字节为单位处理 16 位小端 PCM；采集结果应包含完整采样。
        for (i in 0 until bytesRead step 2) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            if (volume<0x10000) {
                val adjusted = ((sample.toShort() * volume) shr 16).coerceIn(-32768, 32767)
                buffer[i] = adjusted.toByte()
                buffer[i + 1] = (adjusted shr 8).toByte()
                maxAmplitude = maxOf(maxAmplitude, abs(adjusted))
            } else {
                maxAmplitude = maxOf(maxAmplitude, abs(sample))
            }
        }
        return maxAmplitude <= silenceThreshold
    }

    /** 按 channels 创建协议头并循环采集音频；仅发送非静音数据，网络异常时结束线程。 */
    private fun captureAndStream(channels: Number) {
        val buffer = ByteArray(PACKET_SIZE)
        val screamHeader = createScreamHeader(channels)
        val packetBuffer = ByteBuffer.allocate(screamHeader.size + PACKET_SIZE)

        var packetCount = 0

        while (isCapturing) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, PACKET_SIZE) ?: 0
                val isSilent = isSilentAndChangeVolume(buffer, bytesRead, screamVolume)
                if (bytesRead > 0 && !isSilent) {
                    sendScreamPacket(buffer, bytesRead, screamHeader, packetBuffer)
                    packetCount++
                }
            } catch (e: IOException) {
                if (isCapturing) Log.e(TAG, "Error streaming audio", e)
                break
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in capture loop", e)
                break
            }
        }

        Log.i(TAG, "Capture thread ended")
    }

    /** 将 buffer 的前 bytesRead 字节与 screamHeader 写入复用的 packetBuffer，再通过 UDP 发送。 */
    private fun sendScreamPacket(
        buffer: ByteArray,
        bytesRead: Int,
        screamHeader: ByteArray,
        packetBuffer: ByteBuffer
    ) {
        packetBuffer.clear()
        packetBuffer.put(screamHeader)
        packetBuffer.put(buffer, 0, bytesRead)

        val packet = ByteArray(packetBuffer.position())
        packetBuffer.flip()
        packetBuffer.get(packet)

        val udpPacket = DatagramPacket(packet, packet.size, screamServerAddress, screamServerPort)
        socket?.send(udpPacket)
    }

    /**
     * 创建并返回 5 字节的 Scream 协议头。
     * 格式（5 字节）：
     * - 字节 0：最高位选择 44100/48000 Hz 基准，低 7 位为采样率倍数。
     * - 字节 1：每个采样的位数（16 或 24）。
     * - 字节 2：声道数（1–8）。
     * - 字节 3–4：声道映射，采用小端序。
     * @param channels 声道数，当前界面支持单声道和立体声。
     * @return 包含采样率、位深、声道数与声道映射的协议头。
     */
    private fun createScreamHeader(channels: Number): ByteArray {
        return byteArrayOf(
            (0x80 or (MULTIPLIER and 0x7F)).toByte(),  // 采样率的第 7 位：1 选择 44100 Hz 基准，0 选择 48000 Hz 基准。
            BITS_PER_SAMPLE.toByte(), // 每个采样的位数。
            channels.toByte(),      // 声道数。
            when (channels) {   // 声道映射（立体声 = 0x01 | 0x02 = 0x03）。  // https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/ksmedia/ns-ksmedia-waveformatextensible
                1 -> 0x01.toByte()
                else -> 0x03.toByte()
            },
            0x00                    // 声道映射的高字节，按小端序排列。
        )
    }

    /** 停止采集并等待线程退出；必要时恢复设备音量，释放资源并结束前台服务。 */
    private fun stopCapture() {
        Log.i(TAG, "Stopping audio capture")
        isCapturing = false

        captureThread?.let {
            try {
                it.join(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (audioManager!=null) {
            val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (originalVolume != null && currentVolume == 0) audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC,originalVolume!!,0)
        }
        cleanup()
        stopForeground(true)
        stopSelf()
    }

    /** 释放录音器、UDP 套接字和投屏会话；录音器释放异常会记入日志。 */
    private fun cleanup() {
        audioRecord?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing audio record", e)
            }
        }
        audioRecord = null

        socket?.let {
            if (!it.isClosed) {
                it.close()
            }
        }
        socket = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    /** 在支持通知渠道的系统上创建无声音、灯光或振动的推流通知渠道。 */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Scream Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Streaming device audio to Scream receiver"
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    /** 返回持续显示的推流通知；点击打开主界面，移除通知时请求停止采集。 */
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val deleteIntent = Intent(this, ScreamAudioService::class.java).apply {
            action = "STOP_CAPTURE"
        }

        val deletePendingIntent = PendingIntent.getService(
            this,
            0,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentText("Streaming audio to Scream receiver...")
            .setSmallIcon(R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setOngoing(true)
            .setSound(null)

        val notification = builder.build()
        Log.d(TAG, "Notification created")
        return notification
    }

    /** 销毁服务，停止采集并释放媒体会话。 */
    override fun onDestroy() {
        stopCapture()
        mediaSession.release()
        super.onDestroy()
    }

    /** 忽略绑定请求 intent 并返回 null，本服务通过启动命令控制。 */
    override fun onBind(intent: Intent?): IBinder? = null
}