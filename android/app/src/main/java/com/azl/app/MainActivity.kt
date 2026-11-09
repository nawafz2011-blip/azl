package com.azl.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.azl.app.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var pickedUri: Uri? = null
    private var mode = Mode.SPEECH

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            setPicked(uri)
        }
    }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            toast("لازم تسمح بالتخزين عشان نحفظ الملفات")
        } else startJob()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Android 15 draws behind the status bar: pad by the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.toggleMode.check(R.id.btnSpeech)
        b.toggleMode.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            mode = if (id == R.id.btnSong) Mode.SONG else Mode.SPEECH
            b.txtModeHint.setText(if (mode == Mode.SONG) R.string.hint_song else R.string.hint_speech)
        }

        val prefs = getSharedPreferences("azl", MODE_PRIVATE)
        b.switchBest.isChecked = prefs.getBoolean("best_quality", false)
        b.switchBest.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("best_quality", on).apply() }

        b.btnPickFile.setOnClickListener { pickFile.launch(arrayOf("audio/*", "video/*")) }
        b.inputUrlLayout.setEndIconOnClickListener {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            extractUrl(text)?.let { b.inputUrl.setText(it) } ?: toast("ما فيه رابط منسوخ")
        }
        b.inputUrl.doAfterTextChanged {
            if (!it.isNullOrBlank() && pickedUri != null) clearPicked()
            refreshStart()
        }
        b.btnStart.setOnClickListener { onStartClicked() }
        b.btnCancel.setOnClickListener { startService(Intent(this, SeparationService::class.java).setAction(SeparationService.ACTION_CANCEL)) }
        b.btnNew.setOnClickListener {
            JobRepo.update { JobState() }
            clearPicked(); b.inputUrl.setText("")
        }
        b.txtVersion.text = "عزل ${BuildConfig.VERSION_NAME}"

        handleShare(intent)
        observe()
        checkUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Share from YouTube/TikTok (text link) or from the gallery/files (a media file). */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        if (i.type?.startsWith("text/") == true) {
            extractUrl(i.getStringExtra(Intent.EXTRA_TEXT))?.let { clearPicked(); b.inputUrl.setText(it) }
        } else {
            @Suppress("DEPRECATION")
            val uri = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableExtra(Intent.EXTRA_STREAM)
            uri?.let { setPicked(it) }
        }
    }

    private fun extractUrl(text: String?): String? =
        text?.let { Regex("https?://\\S+").find(it)?.value?.trimEnd('.', ',', ')', '»') }

    private fun setPicked(uri: Uri) {
        pickedUri = uri
        b.inputUrl.setText("")
        b.txtFileName.text = Engine.displayName(this, uri) ?: "ملف"
        b.txtFileName.isVisible = true
        refreshStart()
    }

    private fun clearPicked() {
        pickedUri = null
        b.txtFileName.isVisible = false
        refreshStart()
    }

    private fun refreshStart() {
        val hasUrl = extractUrl(b.inputUrl.text?.toString()) != null
        b.btnStart.isEnabled = !JobRepo.state.value.running && (pickedUri != null || hasUrl)
    }

    private fun onStartClicked() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (need.isEmpty()) startJob() else askPermissions.launch(need.toTypedArray())
    }

    private fun startJob() {
        val url = if (pickedUri == null) extractUrl(b.inputUrl.text?.toString()) else null
        if (pickedUri == null && url == null) return
        JobRepo.update { JobState(running = true, stage = "جاري البدء…") }
        SeparationService.start(this, mode, url, pickedUri, if (b.switchBest.isChecked) Model.BEST else Model.FAST)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                JobRepo.state.collect { render(it) }
            }
        }
    }

    private fun render(s: JobState) {
        val idle = !s.running && s.results.isEmpty()
        b.cardInput.isVisible = idle
        b.btnStart.isVisible = idle
        b.cardProgress.isVisible = s.running
        b.txtError.isVisible = s.error != null
        b.txtError.text = s.error
        b.cardResults.isVisible = s.results.isNotEmpty()
        refreshStart()

        if (s.running) {
            b.txtStage.text = s.stage
            b.txtTitle.text = s.title
            b.txtTitle.isVisible = s.title.isNotEmpty()
            if (s.progress in 0f..1f) {
                b.progress.isIndeterminate = false
                b.progress.setProgressCompat((s.progress * 100).toInt(), true)
                b.txtPercent.text = "${(s.progress * 100).toInt()}%"
            } else {
                if (!b.progress.isIndeterminate) {
                    b.progress.visibility = View.INVISIBLE
                    b.progress.isIndeterminate = true
                    b.progress.visibility = View.VISIBLE
                }
                b.txtPercent.text = ""
            }
        }
        if (s.results.isNotEmpty() && b.resultsContainer.tag != s.results) {
            b.resultsContainer.tag = s.results
            b.resultsContainer.removeAllViews()
            s.results.forEach { b.resultsContainer.addView(resultRow(it)) }
        }
    }

    private fun resultRow(r: ResultItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val label = TextView(this).apply {
            text = r.label
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val play = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "تشغيل"
            setOnClickListener {
                open(Intent(Intent.ACTION_VIEW).setDataAndType(r.uri, r.mime))
            }
        }
        val share = MaterialButton(this).apply {
            text = "مشاركة"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).setType(r.mime).putExtra(Intent.EXTRA_STREAM, r.uri)
                open(Intent.createChooser(send, r.label))
            }
        }
        row.addView(label); row.addView(play); row.addView(share)
        return row
    }

    private fun open(i: Intent) {
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(i) }.onFailure { toast("ما فيه تطبيق يفتح الملف") }
    }

    private fun checkUpdate() {
        lifecycleScope.launch {
            val u = withContext(Dispatchers.IO) { Updater.check() } ?: return@launch
            b.cardUpdate.isVisible = true
            b.txtUpdate.text = "فيه نسخة جديدة: ${u.version}"
            b.btnUpdate.setOnClickListener { open(Intent(Intent.ACTION_VIEW, Uri.parse(u.apkUrl))) }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
