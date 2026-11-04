package com.minimal.homelauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

data class AppItem(val label: String, val pkg: String, val cls: String) {
    val key: String get() = "$pkg/$cls"
}

class HomeActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var homeLayer: View
    private lateinit var appsLayer: View
    private lateinit var favContainer: LinearLayout
    private lateinit var listView: ListView
    private lateinit var search: EditText
    private lateinit var bar: AlphabetBar
    private lateinit var detector: GestureDetector
    private val adapter = AppAdapter()

    private var allApps: List<AppItem> = emptyList()
    private var shown: List<AppItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)

        homeLayer = findViewById(R.id.homeLayer)
        appsLayer = findViewById(R.id.appsLayer)
        favContainer = findViewById(R.id.favContainer)
        listView = findViewById(R.id.appList)
        search = findViewById(R.id.search)
        bar = findViewById(R.id.alphabet)

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ -> launch(shown[pos]) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            showAppMenu(shown[pos])
            true
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })
        // Keyboard ka "Go" dabane par pehla result khul jaata hai
        search.setOnEditorActionListener { _, _, _ ->
            shown.firstOrNull()?.let { launch(it) }
            true
        }

        bar.onLetter = { ch -> scrollToLetter(ch) }
        findViewById<View>(R.id.btnAllApps).setOnClickListener { openApps() }

        detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (dy < -dp(80) && abs(dy) > abs(dx) && velocityY < -600) {
                    openApps()
                    return true
                }
                return false
            }
        })
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button dabane par drawer band
        if (Intent.ACTION_MAIN == intent.action) closeApps()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (appsLayer.visibility == View.VISIBLE) closeApps()
        // Launcher par back dabane se kuch nahi hota
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (appsLayer.visibility != View.VISIBLE) detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ---------- Apps ----------

    private fun loadApps() {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        allApps = pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                AppItem(
                    it.loadLabel(pm).toString(),
                    it.activityInfo.packageName,
                    it.activityInfo.name
                )
            }
            .sortedBy { it.label.lowercase() }

        if (!prefs.contains("favs")) saveFavKeys(defaultFavorites())
        applyFilter()
        renderFavorites()
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        shown = if (q.isEmpty()) allApps else allApps.filter { it.label.lowercase().contains(q) }
        adapter.notifyDataSetChanged()
        bar.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun scrollToLetter(ch: Char) {
        if (shown.isEmpty()) return
        val idx = if (ch == '#') 0 else shown.indexOfFirst {
            val c = it.label.firstOrNull()?.uppercaseChar()
            c != null && c >= ch
        }
        listView.setSelection(if (idx >= 0) idx else shown.size - 1)
    }

    private fun launch(app: AppItem) {
        try {
            val i = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.pkg, app.cls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(i)
            closeApps()
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open " + app.label, Toast.LENGTH_SHORT).show()
        }
    }

    private fun appInfo(app: AppItem) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", app.pkg, null))
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open app info", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Favorites ----------

    private fun getFavKeys(): MutableList<String> =
        (prefs.getString("favs", "") ?: "").split("\n").filter { it.isNotBlank() }.toMutableList()

    private fun saveFavKeys(keys: List<String>) {
        prefs.edit().putString("favs", keys.joinToString("\n")).apply()
    }

    private fun defaultFavorites(): List<String> {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        val keys = mutableListOf<String>()
        for (i in intents) {
            val pkg = packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName ?: continue
            val app = allApps.firstOrNull { it.pkg == pkg } ?: continue
            if (app.key !in keys) keys.add(app.key)
        }
        return keys
    }

    private fun renderFavorites() {
        favContainer.removeAllViews()
        val byKey = allApps.associateBy { it.key }
        val favs = getFavKeys().mapNotNull { byKey[it] }

        if (favs.isEmpty()) {
            favContainer.addView(TextView(this).apply {
                text = "Open All apps and long-press an app to add it here"
                setTextColor(0xAAFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            return
        }
        for (app in favs) {
            favContainer.addView(TextView(this).apply {
                text = app.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                setPadding(0, dp(10), 0, dp(10))
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { launch(app) }
                setOnLongClickListener {
                    showFavMenu(app)
                    true
                }
            })
        }
    }

    private fun showFavMenu(app: AppItem) {
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(arrayOf("Move up", "Remove from favorites", "App info")) { _, which ->
                when (which) {
                    0 -> moveUp(app)
                    1 -> {
                        val keys = getFavKeys()
                        keys.remove(app.key)
                        saveFavKeys(keys)
                        renderFavorites()
                    }
                    2 -> appInfo(app)
                }
            }
            .show()
    }

    private fun moveUp(app: AppItem) {
        val keys = getFavKeys()
        val i = keys.indexOf(app.key)
        if (i > 0) {
            keys[i] = keys[i - 1].also { keys[i - 1] = keys[i] }
            saveFavKeys(keys)
            renderFavorites()
        }
    }

    private fun showAppMenu(app: AppItem) {
        val keys = getFavKeys()
        val isFav = app.key in keys
        val favText = if (isFav) "Remove from favorites" else "Add to favorites"
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(arrayOf(favText, "App info")) { _, which ->
                when (which) {
                    0 -> {
                        if (isFav) keys.remove(app.key) else keys.add(app.key)
                        saveFavKeys(keys)
                        renderFavorites()
                    }
                    1 -> appInfo(app)
                }
            }
            .show()
    }

    // ---------- Drawer open/close ----------

    private fun openApps() {
        search.setText("")
        listView.setSelection(0)
        appsLayer.alpha = 0f
        appsLayer.visibility = View.VISIBLE
        appsLayer.animate().alpha(1f).setDuration(150).start()
    }

    private fun closeApps() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
        appsLayer.visibility = View.GONE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- List adapter ----------

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@HomeActivity).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(dp(28), dp(14), dp(40), dp(14))
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            }
            tv.text = shown[position].label
            return tv
        }
    }
}
