/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */
package de.blinkt.openvpn.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import de.blinkt.openvpn.core.Preferences
import android.widget.Toast
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import de.blinkt.openvpn.R
import de.blinkt.openvpn.core.GlobalPreferences
import de.blinkt.openvpn.fragments.*
import de.blinkt.openvpn.fragments.ImportRemoteConfig.Companion.newInstance
import de.blinkt.openvpn.views.ScreenSlidePagerAdapter

class MainActivity : BaseActivity() {
    private val legacyNavigation get() = isAndroidTV || GlobalPreferences.getMinimalUi()
    private lateinit var navigation: BottomNavigationView
    private var destination = R.id.go_home
    private val setupPreferences by lazy { Preferences.getDefaultSharedPreferences(this) }

    private val navigateBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (currentPage() is PermissionSetup) finish()
            else if (currentPage() is FaqFragment || currentPage() is AboutFragment || currentPage() is SendDumpFragment)
                display(R.id.go_settings)
            else display(R.id.go_home)
        }
    }

    private lateinit var mPager: ViewPager2
    private lateinit var mPagerAdapter: ScreenSlidePagerAdapter


    override fun onCreate(savedInstanceState: Bundle?) {
        if (legacyNavigation) setTheme(R.style.blinkt)
        super.onCreate(savedInstanceState)
        if (legacyNavigation) {
            title = getString(R.string.brand_short)
            createLegacyNavigation()
            return
        }
        setContentView(R.layout.navigation_shell)
        applyShellInsets(R.id.shell_root)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.shell_toolbar))
        navigation = findViewById(R.id.navigation)
        destination = savedInstanceState?.getInt("destination", R.id.go_home) ?: R.id.go_home
        navigation.menu.findItem(destination).isChecked = true
        navigation.setOnItemSelectedListener {
            display(it.itemId)
            true
        }
        onBackPressedDispatcher.addCallback(this, navigateBack)
        if (currentPage() == null) {
            if (setupPreferences.getBoolean("permissionSetupComplete", false)) display(destination)
            else showPage(PermissionSetup())
        } else updateChrome()
    }

    private fun currentPage() = supportFragmentManager.findFragmentById(R.id.shell_content)

    private fun showPage(page: Fragment) {
        supportFragmentManager.beginTransaction().setReorderingAllowed(true)
            .replace(R.id.shell_content, page).commitNow()
        updateChrome()
    }

    private fun updateChrome() {
        val page = currentPage()
        val setup = page is PermissionSetup
        val detail = page is FaqFragment || page is AboutFragment || page is SendDumpFragment
        navigation.visibility = if (setup) View.GONE else View.VISIBLE
        supportActionBar?.setDisplayHomeAsUpEnabled(detail)
        title = getString(when (page) {
            is FaqFragment -> R.string.faq
            is AboutFragment -> R.string.about
            is SendDumpFragment -> R.string.crashdump
            else -> R.string.brand_short
        })
        navigateBack.isEnabled = setup || detail || destination != R.id.go_home
        invalidateOptionsMenu()
    }

    fun finishSetup() {
        setupPreferences.edit().putBoolean("permissionSetupComplete", true).apply()
        display(R.id.go_home)
        handleEntryIntent()
    }

    fun display(id: Int) {
        destination = id
        navigation.menu.findItem(id).isChecked = true
        val fragment = when (id) {
            R.id.go_profiles -> VPNProfileList()
            R.id.go_settings -> GeneralSettings()
            else -> HomeFragment()
        }
        if (currentPage()?.javaClass != fragment.javaClass) showPage(fragment)
    }

    fun showSettingsPage(page: Fragment) = showPage(page)

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("destination", destination)
        super.onSaveInstanceState(outState)
    }

    private fun createLegacyNavigation() {
        val view = layoutInflater.inflate(R.layout.main_activity, null)

        // Instantiate a ViewPager and a PagerAdapter.
        mPager = view.findViewById(R.id.pager)
        val tablayout: TabLayout = view.findViewById(R.id.tab_layout)

        mPagerAdapter = ScreenSlidePagerAdapter(supportFragmentManager, lifecycle, this)

        /* Toolbar and slider should have the same elevation */
        disableToolbarElevation()

        val minimalUi = GlobalPreferences.getMinimalUi();
        if (isAndroidTV || minimalUi) {
            mPagerAdapter.addTab(R.string.minimal_ui, MinimalUI::class.java)
        }
        if (!minimalUi) {

            mPagerAdapter.addTab(R.string.vpn_list_title, VPNProfileList::class.java)
            mPagerAdapter.addTab(R.string.graph, GraphFragment::class.java)
            mPagerAdapter.addTab(R.string.generalsettings, GeneralSettings::class.java)
            mPagerAdapter.addTab(R.string.faq, FaqFragment::class.java)
            if (SendDumpFragment.getLatestDump(this) != null) {
                mPagerAdapter.addTab(R.string.crashdump, SendDumpFragment::class.java)
            }

        }
        if (isAndroidTV || minimalUi)
            mPagerAdapter.addTab(R.string.openvpn_log, LogFragment::class.java)

        mPagerAdapter.addTab(R.string.about, AboutFragment::class.java)
        mPager.setAdapter(mPagerAdapter)

        TabLayoutMediator(tablayout, mPager) { tab, position ->
            tab.text = mPagerAdapter.getPageTitle(position)
        }.attach()

        setUpEdgeEdgeInsetsListener(view, R.id.root_linear_layout)
        setContentView(view)
    }


    private fun disableToolbarElevation() {
        supportActionBar?.elevation = 0f
    }

    override fun onResume() {
        super.onResume()
        if (!legacyNavigation && currentPage() is PermissionSetup) return
        handleEntryIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun handleEntryIntent() {
        val intent = intent
        if (intent != null) {
            val action = intent.action
            if (Intent.ACTION_VIEW == action) {
                val uri = intent.data
                uri?.let { checkUriForProfileImport(it) }
            }
            val page = intent.getStringExtra("PAGE")
            if ("graph" == page) {
                if (!GlobalPreferences.getMinimalUi())
                    startActivity(Intent(this, LogWindow::class.java).putExtra("PAGE", "graph"))
            }
            if (!legacyNavigation && intent.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES")
                display(R.id.go_settings)
            setIntent(null)
        }
    }

    private fun checkUriForProfileImport(uri: Uri) {
        if ("openvpn" == uri.scheme && "import-profile" == uri.host) {
            var realUrl = uri.encodedPath + "?" + uri.encodedQuery
            if (!realUrl.startsWith("/https://")) {
                Toast.makeText(
                    this,
                    "Cannot use openvpn://import-profile/ URL that does not use https://",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            realUrl = realUrl.substring(1)
            startOpenVPNUrlImport(realUrl)
        }
    }

    private fun startOpenVPNUrlImport(url: String) {
        val asImportFrag = newInstance(url)
        asImportFrag.show(supportFragmentManager, "dialog")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (!GlobalPreferences.getMinimalUi() && (legacyNavigation || currentPage() !is PermissionSetup))
            menuInflater.inflate(R.menu.main_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.show_log) {
            val showLog = Intent(this, LogWindow::class.java)
            startActivity(showLog)
            return true
        }
        if (item.itemId == android.R.id.home && !legacyNavigation) {
            navigateBack.handleOnBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}