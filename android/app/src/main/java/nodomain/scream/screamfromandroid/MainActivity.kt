package nodomain.scream.screamfromandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 控制 Scream 音频推流的主界面。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1
        private const val REQUEST_RECORD_AUDIO = 2
    }

    private var permissionLauncher: ActivityResultLauncher<Array<String>?> = getRequestPermissionsLauncher()
    private lateinit var serverIpEditText: EditText
    private lateinit var serverPortEditText: EditText
    private lateinit var button: Button
    private lateinit var channels: RadioGroup
    private lateinit var mute: CheckBox

    private var mediaProjectionResultCode: Int = -1
    private var mediaProjectionResultData: Intent? = null
    private var isStreaming = false

    /** 返回输入框文本，并去除首尾空白。 */
    fun EditText.getTrimmedString():String { return this.text.toString().trim() }

    /** 根据 savedInstanceState 创建界面，恢复配置并注册配置保存监听器。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = this.applicationContext.getSharedPreferences("user_prefs", MODE_PRIVATE)
        serverIpEditText = findViewById(R.id.serverIpEditText)
        serverPortEditText = findViewById(R.id.serverPortEditText)
        button = findViewById(R.id.button)
        channels = findViewById( R.id.channels)
        mute = findViewById( R.id.mute)

        // 优先读取已保存的配置，未设置时使用默认值。
        serverIpEditText.setText(prefs.getString("SCREAM_IP", ScreamAudioService.SCREAM_IP))
        serverPortEditText.setText(prefs.getInt("SCREAM_PORT", ScreamAudioService.SCREAM_PORT).toString())
        channels.check(when (prefs.getInt("SCREAM_CHANNELS", 2)) {
            1 ->  R.id.mono
            else -> R.id.stereo
        })
        mute.isChecked = prefs.getBoolean("SCREAM_MUTE", true)

        // 在配置变更时持久化，供后台采集服务读取。
        serverIpEditText.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { if (serverIpEditText.getTrimmedString() == "") serverIpEditText.setText(ScreamAudioService.SCREAM_IP); prefs.edit().putString("SCREAM_IP", serverIpEditText.getTrimmedString()).apply() }}
        serverPortEditText.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { if (serverPortEditText.getTrimmedString() == "") serverPortEditText.setText(ScreamAudioService.SCREAM_PORT.toString()); prefs.edit().putInt("SCREAM_PORT", serverPortEditText.getTrimmedString().toInt()).apply() }}
        channels.setOnCheckedChangeListener { group, checkedId ->
            val channels = when (checkedId) {
                R.id.mono -> 1
                else -> 2   // 默认使用立体声。
            }
            prefs.edit().putInt("SCREAM_CHANNELS", channels).apply()
        }
        mute.setOnCheckedChangeListener { buttonView, isChecked -> prefs.edit().putBoolean("SCREAM_MUTE", isChecked).apply()        }

        updateButtonStates()
    }

    /** 返回当前检查列表中的权限是否全部已授予。 */
    fun hasPermissions(): Boolean {
        return getMissingPermissions().isEmpty()
    }

    /** 根据系统版本返回上游要求检查的权限列表。 */
    fun getPermissions(): MutableList<String> {
        val runtimePermissions = mutableListOf<String>()

        // RECORD_AUDIO 需要运行时授权。
        runtimePermissions.add(Manifest.permission.RECORD_AUDIO)

        // Android 13 及以上版本需要运行时申请 POST_NOTIFICATIONS。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runtimePermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 保留上游从 Android 10 起检查的投屏前台服务权限项。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runtimePermissions.add(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
        }
        return runtimePermissions
    }

    /** 返回权限列表中尚未授予的权限数组。 */
    fun getMissingPermissions(): Array<String> {
        val runtimePermissions = getPermissions()
        // 只返回当前尚未授予的权限。
        val notGranted = runtimePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        return notGranted
    }

    /** 注册并返回权限申请入口；拒绝授权时提示用户并打开应用设置。 */
    fun getRequestPermissionsLauncher(): ActivityResultLauncher<Array<String>?> {
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            results.forEach { (perm, runtimePermissions) ->
                if (!runtimePermissions) {
                    Toast.makeText(
                        this,
                        "Required permission denied: $perm",
                        Toast.LENGTH_SHORT
                    ).show()
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                }
            }
            updateButtonStates()
        }
        return permissionLauncher
    }
    /** 通过已注册的入口申请当前缺失的权限。 */
    fun requestPermissions() {
        permissionLauncher.launch(getMissingPermissions())
    }

    /** 在需要运行时授权的系统上申请录音权限。 */
    private fun requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_RECORD_AUDIO
                )
            }
        }
    }

    /** 校验输入和录音授权，然后向系统请求屏幕捕获授权以开始音频推流。 */
    private fun startStreaming() {
        requestPermissions()
        val serverIp = serverIpEditText.getTrimmedString()
        val portStr = serverPortEditText.getTrimmedString()

        if (serverIp.isEmpty()) {
            Toast.makeText(this, "Please enter server IP address", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            portStr.toInt()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show()
            return
        }

        // 开始采集前检查录音权限。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "Audio recording permission required", Toast.LENGTH_SHORT).show()
                requestAudioPermission()
                return
            }
        }

        // 请求 MediaProjection 授权以采集设备播放的音频。
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
    }

    /** 通知服务停止采集，并更新界面的推流状态。 */
    private fun stopStreaming() {
        val intent = Intent(this, ScreamAudioService::class.java).apply {
            action = "STOP_CAPTURE"
        }
        startService(intent)

        isStreaming = false
        updateButtonStates()
        Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show()
    }

    /** 处理 requestCode 对应的授权结果；resultCode 和 data 有效时启动前台采集服务。 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                mediaProjectionResultCode = resultCode
                mediaProjectionResultData = data

                val serverIp = serverIpEditText.getTrimmedString()
                val port = serverPortEditText.getTrimmedString().toIntOrNull() ?: 4010

                val intent = Intent(this, ScreamAudioService::class.java).apply {
                    action = "START_CAPTURE"
                    putExtra("SERVER_IP", serverIp)
                    putExtra("SERVER_PORT", port)
                    putExtra("RESULT_CODE", resultCode)
                    putExtra("RESULT_DATA", data)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }

                isStreaming = true
                updateButtonStates()
                Toast.makeText(this, "Streaming started to $serverIp:$port", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 根据 requestCode、permissions 和 grantResults 显示录音权限申请结果。 */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Audio permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Audio permission is required for streaming",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** 根据授权和推流状态更新控件可用性、按钮文本及点击行为。 */
    private fun updateButtonStates() {
        serverIpEditText.isEnabled = !isStreaming
        serverPortEditText.isEnabled = !isStreaming
        for (i in 0 until channels.childCount) channels.getChildAt(i).isEnabled = !isStreaming
        mute.isEnabled = !isStreaming

        if (!hasPermissions()) {
            button.setBackgroundColor(TypedValue().also { theme.resolveAttribute(com.google.android.material.R.attr.colorButtonNormal, it, true) }.data)
            button.setText("Request audio permissions")
            button.setOnClickListener { requestPermissions() }
        } else if (isStreaming) {
            button.setBackgroundColor(ContextCompat.getColor(this, R.color.stop))
            button.setText("Stop streaming")
            button.setOnClickListener { stopStreaming() }
        } else {
            button.setBackgroundColor(ContextCompat.getColor(this, R.color.start))
            button.setText("Start streaming")
            button.setOnClickListener { startStreaming() }
        }

    }

    /** 销毁界面；若正在推流，则同时请求服务停止采集。 */
    override fun onDestroy() {
        super.onDestroy()
        if (isStreaming) {
            stopStreaming()
        }
    }
}