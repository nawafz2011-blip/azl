package com.azl.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.azl.app.databinding.ActivityMainBinding
import com.azl.app.databinding.ItemHistoryBinding
import com.azl.app.databinding.ItemTrackBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val home get() = b.home
    private val prefs by lazy { getSharedPreferences("azl", MODE_PRIVATE) }

    private var pickedUri: Uri? = null
    private var mode = Mode.SPEECH
    private var shownResults: List<ResultItem>? = null

    // ---------------------------------------------------------------- pickers / permissions
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

        // Android 15 draws behind the system bars: pad the pages and the menu.
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            listOf(b.home.root, b.history.root, b.settings.root).forEach { it.updatePadding(top = bars.top) }
            b.nav.updatePadding(bottom = bars.bottom)
            b.root.updatePadding(bottom = if (ime.bottom > bars.bottom) ime.bottom - bars.bottom else 0)
            insets
        }

        setupNav(savedInstanceState?.getInt("tab") ?: R.id.nav_home)
        setupHome()
        setupSettings()
        handleShare(intent)
        observe()
        observePlayer()
        checkUpdate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", b.nav.selectedItemId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onStop() {
        super.onStop()
        // the video can't play without the screen; the audio tracks keep playing in the background
        if (home.video.isPlaying) { home.video.pause(); home.videoPlay.isVisible = true }
        handler.removeCallbacks(tick)
    }

    // ---------------------------------------------------------------- navigation
    private fun setupNav(tab: Int) {
        b.nav.setOnItemSelectedListener { item ->
            showTab(item.itemId); true
        }
        b.nav.selectedItemId = tab
        showTab(tab)
    }

    private fun showTab(id: Int) {
        b.home.root.isVisible = id == R.id.nav_home
        b.history.root.isVisible = id == R.id.nav_history
        b.settings.root.isVisible = id == R.id.nav_settings
        if (id == R.id.nav_history) renderHistory()
        if (id == R.id.nav_settings) refreshModelsSize()
    }

    // ---------------------------------------------------------------- home
    private fun setupHome() {
        mode = runCatching { Mode.valueOf(prefs.getString("mode", "SPEECH")!!) }.getOrDefault(Mode.SPEECH)
        home.cardSpeech.setOnClickListener { selectMode(Mode.SPEECH) }
        home.cardSong.setOnClickListener { selectMode(Mode.SONG) }
        selectMode(mode)

        home.pickArea.setOnClickListener { pickFile.launch(arrayOf("audio/*", "video/*")) }
        home.btnPaste.setOnClickListener {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            extractUrl(text)?.let { home.inputUrl.setText(it) } ?: toast("ما فيه رابط منسوخ")
        }
        home.inputUrl.doAfterTextChanged {
            if (!it.isNullOrBlank() && pickedUri != null) clearPicked()
            refreshStart()
        }
        home.btnStart.setOnClickListener { onStartClicked() }
        home.btnCancel.setOnClickListener {
            startService(Intent(this, SeparationService::class.java).setAction(SeparationService.ACTION_CANCEL))
        }
        home.btnNew.setOnClickListener {
            stopAudio()
            JobRepo.update { JobState() }
            clearPicked(); home.inputUrl.setText("")
            home.scroll.scrollTo(0, 0)
        }
        home.btnShareAll.setOnClickListener { shownResults?.let { shareAll(it) } }
        home.btnHeader.setOnClickListener { onHeaderButton() }
        buildSteps()
    }

    private fun selectMode(m: Mode) {
        mode = m
        prefs.edit().putString("mode", m.name).apply()
        styleModeCard(home.cardSpeech, home.iconSpeech, m == Mode.SPEECH)
        styleModeCard(home.cardSong, home.iconSong, m == Mode.SONG)
    }

    private fun styleModeCard(card: com.google.android.material.card.MaterialCardView, icon: ImageView, on: Boolean) {
        card.isChecked = on
        card.setCardBackgroundColor(color(if (on) R.color.accent_soft else R.color.card))
        icon.setBackgroundResource(if (on) R.drawable.bg_icon_acc else R.drawable.bg_icon_plain)
        icon.imageTintList = ColorStateList.valueOf(color(if (on) R.color.accent_ink else R.color.muted))
    }

    private fun onHeaderButton() {
        val s = JobRepo.state.value
        if (s.results.isNotEmpty()) { shareAll(s.results); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("كيف أستخدم عزل؟")
            .setMessage("1. اختر: إزالة الموسيقى (يبقى الكلام) أو فصل أغنية (صوت وموسيقى).\n\n" +
                "2. اختر ملف من جوالك، أو ألصق رابط من يوتيوب أو تيك توك. وتقدر تشارك المقطع من تطبيقه لـ«عزل» مباشرة.\n\n" +
                "3. اضغط «ابدأ العزل». تقدر تطلع من التطبيق، ويجيك إشعار لما يخلص.\n\n" +
                "الملفات تنحفظ في جوالك داخل مجلد «عزل»، وتلقاها كلها في «السجل».")
            .setPositiveButton("تمام", null)
            .show()
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        b.nav.selectedItemId = R.id.nav_home
        if (i.type?.startsWith("text/") == true) {
            extractUrl(i.getStringExtra(Intent.EXTRA_TEXT))?.let { clearPicked(); home.inputUrl.setText(it) }
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
        home.inputUrl.setText("")
        home.txtPickTitle.text = Engine.displayName(this, uri) ?: "ملف"
        home.txtPickSub.text = "اضغط لتغيير الملف"
        home.iconPick.setImageResource(R.drawable.ic_check)
        refreshStart()
    }

    private fun clearPicked() {
        pickedUri = null
        home.txtPickTitle.setText(R.string.pick_file)
        home.txtPickSub.setText(R.string.pick_sub)
        home.iconPick.setImageResource(R.drawable.ic_plus)
        refreshStart()
    }

    private fun refreshStart() {
        val hasUrl = extractUrl(home.inputUrl.text?.toString()) != null
        home.btnStart.isEnabled = !JobRepo.state.value.running && (pickedUri != null || hasUrl)
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
        val url = if (pickedUri == null) extractUrl(home.inputUrl.text?.toString()) else null
        if (pickedUri == null && url == null) return
        JobRepo.update { JobState(running = true, stage = "جاري البدء…") }
        val model = if (prefs.getBoolean("best_quality", false)) Model.BEST else Model.FAST
        SeparationService.start(this, mode, url, pickedUri, model)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                JobRepo.state.collect { render(it) }
            }
        }
    }

    // ---------------------------------------------------------------- render job state
    private fun render(s: JobState) {
        val hasResults = s.results.isNotEmpty() && !s.running
        home.secInput.isVisible = !s.running && !hasResults
        home.secProgress.isVisible = s.running
        home.secResult.isVisible = hasResults
        home.txtSub.isVisible = !s.running && !hasResults
        home.txtHeader.text = when {
            s.running -> "جاري العزل"
            hasResults -> "النتيجة"
            else -> getString(R.string.app_name)
        }
        home.btnHeader.setImageResource(if (hasResults) R.drawable.ic_share else R.drawable.ic_help)
        home.btnHeader.isVisible = !s.running
        home.txtError.isVisible = s.error != null
        home.txtError.text = s.error
        refreshStart()

        if (s.running) renderProgress(s)
        if (hasResults && shownResults !== s.results) showResults(s.title, s.results)
        if (!hasResults && shownResults != null) { shownResults = null }
    }

    private val stepNames = listOf("تجهيز المقطع", "قراءة الصوت", "عزل الصوت عن الموسيقى", "حفظ الملفات في الجوال")
    private val ringLabels = listOf("تجهيز المقطع", "قراءة الصوت", "عزل الصوت", "حفظ الملفات")
    private val stepViews = mutableListOf<Triple<TextView, TextView, TextView>>() // dot, name, value

    private fun buildSteps() {
        val c = home.stepsContainer
        stepNames.forEachIndexed { i, name ->
            if (i > 0) c.addView(View(this).apply {
                setBackgroundColor(color(R.color.line))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    marginStart = dp(14); marginEnd = dp(14)
                }
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
            }
            val dot = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            }
            val label = TextView(this).apply {
                text = name
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
            }
            val value = TextView(this).apply { textSize = 12f; setTextColor(color(R.color.muted)) }
            row.addView(dot); row.addView(label); row.addView(value)
            c.addView(row)
            stepViews += Triple(dot, label, value)
        }
    }

    private fun renderProgress(s: JobState) {
        home.txtJobTitle.text = s.title.ifEmpty { s.stage }
        home.txtEta.text = if (s.eta.isNotEmpty()) "باقي تقريبًا ${s.eta}" else s.stage
        home.txtRingLabel.text = ringLabels.getOrElse(s.step) { "" }
        if (s.progress in 0f..1f) {
            if (home.ring.isIndeterminate) {
                home.ring.visibility = View.INVISIBLE
                home.ring.isIndeterminate = false
                home.ring.visibility = View.VISIBLE
            }
            home.ring.setProgressCompat((s.progress * 1000).toInt(), true)
            home.txtPercent.text = "${(s.progress * 100).toInt()}%"
        } else {
            if (!home.ring.isIndeterminate) {
                home.ring.visibility = View.INVISIBLE
                home.ring.isIndeterminate = true
                home.ring.visibility = View.VISIBLE
            }
            home.txtPercent.text = "…"
        }
        stepViews.forEachIndexed { i, (dot, label, value) ->
            when {
                i < s.step -> {
                    dot.text = "✓"; dot.setBackgroundResource(R.drawable.bg_circle_soft)
                    dot.setTextColor(color(R.color.accent)); label.setTextColor(color(R.color.ink))
                    label.paint.isFakeBoldText = false; value.text = ""
                }
                i == s.step -> {
                    dot.text = "${i + 1}"; dot.setBackgroundResource(R.drawable.bg_circle_acc)
                    dot.setTextColor(color(R.color.accent_ink)); label.setTextColor(color(R.color.ink))
                    label.paint.isFakeBoldText = true
                    value.text = if (s.progress in 0f..1f) "${(s.progress * 100).toInt()}%" else ""
                }
                else -> {
                    dot.text = "${i + 1}"; dot.setBackgroundResource(R.drawable.bg_circle_plain)
                    dot.setTextColor(color(R.color.muted)); label.setTextColor(color(R.color.muted))
                    label.paint.isFakeBoldText = false; value.text = ""
                }
            }
            label.invalidate()
        }
    }

    // ---------------------------------------------------------------- results + playback
    private fun showResults(title: String, results: List<ResultItem>) {
        shownResults = results
        val video = results.firstOrNull { it.isVideo }
        home.txtResultDone.text = if (video != null) "تم حفظ الفيديو في الاستوديو ✓" else "تم العزل ✓"
        home.txtResultTitle.text = title
        home.videoCard.isVisible = video != null
        if (video != null) setupVideo(video.uri)
        home.tracksContainer.removeAllViews()
        results.filter { !it.isVideo }.forEach { r -> home.tracksContainer.addView(trackRow(r).root) }
        home.scroll.post { home.scroll.scrollTo(0, 0) }
    }

    private fun setupVideo(uri: Uri) {
        val v = home.video
        v.setVideoURI(uri)
        v.seekTo(1)
        home.videoPlay.isVisible = true
        val toggle = View.OnClickListener {
            if (v.isPlaying) { v.pause(); home.videoPlay.isVisible = true }
            else { if (AudioPlayer.state.value.playing) AudioPlayer.toggle(this, AudioPlayer.state.value.uri!!, ""); v.start(); home.videoPlay.isVisible = false }
        }
        home.videoPlay.setOnClickListener(toggle)
        home.videoCard.setOnClickListener(toggle)
        v.setOnCompletionListener { home.videoPlay.isVisible = true }
    }

    /** rows on screen that can play, so we can update the one that is playing */
    private val rows = mutableListOf<Pair<ItemTrackBinding, ResultItem>>()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val cur = AudioPlayer.state.value
            rows.forEach { (row, r) -> if (r.uri == cur.uri) row.wave.progress = AudioPlayer.progress() }
            if (cur.playing) handler.postDelayed(this, 80)
        }
    }

    private fun trackRow(r: ResultItem, parent: LinearLayout = home.tracksContainer): ItemTrackBinding {
        val row = ItemTrackBinding.inflate(layoutInflater, parent, false)
        row.txtLabel.text = r.label
        row.wave.peaks = r.peaks
        val isMusic = r.label.contains("الموسيقى")
        row.btnPlay.setBackgroundResource(if (isMusic) R.drawable.bg_circle_plain else R.drawable.bg_circle_acc)
        row.btnPlay.imageTintList = ColorStateList.valueOf(color(if (isMusic) R.color.ink else R.color.accent_ink))
        val title = "${r.label} · ${JobRepo.state.value.title}".trimEnd(' ', '·')
        val play = View.OnClickListener {
            if (home.video.isPlaying) { home.video.pause(); home.videoPlay.isVisible = true }
            if (!AudioPlayer.toggle(this, r.uri, title)) toast("ما قدرت أشغّل الملف. يمكن انحذف من الجوال")
        }
        row.btnPlay.setOnClickListener(play)
        row.root.setOnClickListener(play)
        row.btnShare.setOnClickListener { share(r) }
        rows.removeAll { it.first.root.parent == null && it.first !== row }
        rows += row to r
        paintRow(row, r, AudioPlayer.state.value)
        return row
    }

    private fun paintRow(row: ItemTrackBinding, r: ResultItem, s: PlayState) {
        val current = s.uri == r.uri
        row.btnPlay.setImageResource(if (current && s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        row.wave.progress = if (current) AudioPlayer.progress() else 1f
    }

    private fun observePlayer() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudioPlayer.state.collect { s ->
                    rows.forEach { (row, r) -> paintRow(row, r, s) }
                    handler.removeCallbacks(tick)
                    if (s.playing) handler.post(tick)
                }
            }
        }
    }

    /** Stops the sound (used when starting over). Leaving the app does NOT stop it. */
    private fun stopAudio() {
        if (AudioPlayer.state.value.uri != null) AudioPlayer.stop(this)
    }

    private fun share(r: ResultItem) {
        val send = Intent(Intent.ACTION_SEND).setType(r.mime).putExtra(Intent.EXTRA_STREAM, r.uri)
        open(Intent.createChooser(send, r.label))
    }

    private fun shareAll(results: List<ResultItem>) {
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(results.map { it.uri }))
        open(Intent.createChooser(send, "مشاركة"))
    }

    private fun open(i: Intent) {
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(i) }.onFailure { toast("ما فيه تطبيق يفتح الملف") }
    }

    // ---------------------------------------------------------------- history
    private fun renderHistory() {
        val list = History.load(this)
        val h = b.history
        h.list.removeAllViews()
        h.txtEmpty.isVisible = list.isEmpty()
        h.list.isVisible = list.isNotEmpty()
        h.btnClear.isVisible = list.isNotEmpty()
        h.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage("تبي تمسح السجل؟ الملفات نفسها تبقى في جوالك.")
                .setPositiveButton("امسح") { _, _ -> History.clear(this); renderHistory() }
                .setNegativeButton("لا", null)
                .show()
        }
        list.forEach { e ->
            val row = ItemHistoryBinding.inflate(layoutInflater, h.list, false)
            row.txtTitle.text = e.title
            row.txtBadge.text = if (e.mode == Mode.SPEECH) "إزالة الموسيقى" else "فصل أغنية"
            row.txtTime.text = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            row.icon.setImageResource(if (e.results.any { it.isVideo }) R.drawable.ic_movie else R.drawable.ic_music)
            row.root.setOnClickListener { openHistory(e) }
            row.root.setOnLongClickListener {
                MaterialAlertDialogBuilder(this)
                    .setMessage("تشيل «${e.title}» من السجل؟ الملفات تبقى في جوالك.")
                    .setPositiveButton("شيله") { _, _ -> History.remove(this, e.time); renderHistory() }
                    .setNegativeButton("لا", null)
                    .show()
                true
            }
            h.list.addView(row.root)
        }
    }

    private fun openHistory(e: HistoryEntry) {
        val sheet = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        box.addView(TextView(this).apply {
            text = e.title; textSize = 17f; setTextColor(color(R.color.ink)); paint.isFakeBoldText = true
            setPadding(dp(4), dp(8), dp(4), dp(4))
        })
        e.results.forEach { r ->
            if (r.isVideo) {
                box.addView(com.google.android.material.button.MaterialButton(this).apply {
                    text = "▶  تشغيل الفيديو بدون موسيقى"
                    setOnClickListener { open(Intent(Intent.ACTION_VIEW).setDataAndType(r.uri, r.mime)) }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(10) }
                })
            } else box.addView(trackRow(r, box).root)
        }
        sheet.setContentView(box)
        sheet.show()
    }

    // ---------------------------------------------------------------- settings
    private fun setupSettings() {
        val st = b.settings
        st.txtVersion.text = BuildConfig.VERSION_NAME

        when (prefs.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)) {
            AppCompatDelegate.MODE_NIGHT_NO -> st.groupTheme.check(R.id.themeLight)
            AppCompatDelegate.MODE_NIGHT_YES -> st.groupTheme.check(R.id.themeDark)
            else -> st.groupTheme.check(R.id.themeAuto)
        }
        st.groupTheme.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val m = when (id) {
                R.id.themeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.themeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (m != prefs.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)) {
                prefs.edit().putInt("theme", m).apply()
                AppCompatDelegate.setDefaultNightMode(m)
            }
        }

        val best = prefs.getBoolean("best_quality", false)
        st.groupQuality.check(if (best) R.id.qualityBest else R.id.qualityFast)
        qualityHint(best)
        st.groupQuality.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val on = id == R.id.qualityBest
            prefs.edit().putBoolean("best_quality", on).apply()
            qualityHint(on)
        }

        st.switchVideo.isChecked = prefs.getBoolean("make_video", true)
        st.switchVideo.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("make_video", on).apply() }

        st.rowModels.setOnClickListener {
            if (JobRepo.state.value.running) { toast("انتظر لين يخلص العزل"); return@setOnClickListener }
            MaterialAlertDialogBuilder(this)
                .setMessage("تبي تمسح نماذج العزل؟ بتتحمّل من جديد أول ما تعزل.")
                .setPositiveButton("امسح") { _, _ -> File(filesDir, "models").deleteRecursively(); refreshModelsSize() }
                .setNegativeButton("لا", null)
                .show()
        }
        st.rowSource.setOnClickListener { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nawafz2011-blip/azl"))) }
    }

    private fun qualityHint(best: Boolean) {
        b.settings.txtQualityHint.text = if (best) "أدق نتيجة، بس أبطأ 3 مرات تقريبًا. يحمّل نموذج 67 ميقا أول مرة."
        else "أسرع 3 مرات تقريبًا، بجودة قريبة جدًا من الأدق."
    }

    private fun refreshModelsSize() {
        val bytes = File(filesDir, "models").listFiles()?.sumOf { it.length() } ?: 0L
        b.settings.txtModels.text = if (bytes == 0L) "ما فيه" else "${bytes / 1_000_000} ميقا"
    }

    // ---------------------------------------------------------------- update
    private var pendingUpdate: Updater.Update? = null
    private var updating = false
    private var askedInstallPermission = false

    private fun checkUpdate() {
        lifecycleScope.launch {
            val u = withContext(Dispatchers.IO) { Updater.check() } ?: return@launch
            pendingUpdate = u
            val st = b.settings
            st.cardUpdate.isVisible = true
            st.txtUpdate.text = "فيه تحديث جديد: ${u.version}"
            st.btnUpdate.setOnClickListener { installUpdate() }
            b.nav.getOrCreateBadge(R.id.nav_settings).isVisible = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (askedInstallPermission && packageManager.canRequestPackageInstalls()) {
            askedInstallPermission = false
            installUpdate()
        }
    }

    /** Download the new APK inside the app, then open the system installer: it updates in place. */
    private fun installUpdate() {
        val u = pendingUpdate ?: return
        if (updating) return
        val st = b.settings
        if (!packageManager.canRequestPackageInstalls()) {
            toast("اسمح لـ «عزل» بتثبيت التحديثات، وارجع للتطبيق")
            askedInstallPermission = true
            open(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        updating = true
        st.btnUpdate.isEnabled = false
        st.txtUpdateSub.text = "جاري تحميل التحديث…"
        lifecycleScope.launch {
            val apk = runCatching {
                withContext(Dispatchers.IO) {
                    Updater.download(this@MainActivity, u.apkUrl) { p ->
                        runOnUiThread { st.txtUpdateSub.text = "جاري تحميل التحديث… ${(p * 100).toInt()}%" }
                    }
                }
            }.getOrNull()
            updating = false
            st.btnUpdate.isEnabled = true
            if (apk == null) {
                st.txtUpdateSub.text = "تعذّر تحميل التحديث. اضغط وجرّب مرة ثانية"
                return@launch
            }
            st.txtUpdateSub.text = "جاهز للتثبيت"
            val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.files", apk)
            open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive"))
        }
    }

    // ---------------------------------------------------------------- helpers
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
