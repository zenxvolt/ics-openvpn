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
import android.widget.Toast
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import de.blinkt.openvpn.R
import android.widget.TextView
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.core.GlobalPreferences
import de.blinkt.openvpn.fragments.*
import de.blinkt.openvpn.fragments.ImportRemoteConfig.Companion.newInstance
import de.blinkt.openvpn.views.ScreenSlidePagerAdapter

class MainActivity : BaseActivity(), VpnStatus.StateListener {
    private fun refreshVpnStatus() {
        synchronized(VpnStatus::class.java) {
            val text = if (VpnStatus.isStatusAvailable())
                VpnStatus.getLastCleanLogMessage(this, true).ifEmpty { getString(R.string.state_disconnected) }
            else getString(R.string.qs_syncing)
            findViewById<TextView>(R.id.dashboard_status)?.text = text
            val profile = if (VpnStatus.isVPNActive()) ProfileManager.get(
                this, VpnStatus.getLastConnectedVPNProfile()) else null
            findViewById<TextView>(R.id.dashboard_profile)?.text =
                profile?.name ?: getString(R.string.vpn_choose_profile)
        }
    }

    override fun updateState(state: String?, message: String?, resId: Int,
                             level: ConnectionStatus?, intent: Intent?) {
        runOnUiThread { if (!isDestroyed) refreshVpnStatus() }
    }

    override fun setConnectedVPN(uuid: String?) {
        runOnUiThread { if (!isDestroyed) refreshVpnStatus() }
    }

    override fun onStart() {
        super.onStart()
        VpnStatus.addStateListener(this)
    }

    override fun onStop() {
        VpnStatus.removeStateListener(this)
        super.onStop()
    }

    private lateinit var mPager: ViewPager2
    private lateinit var mPagerAdapter: ScreenSlidePagerAdapter


    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.blinkt_mainTheme)
        super.onCreate(savedInstanceState)
        val view = layoutInflater.inflate(R.layout.main_activity, null)

        setSupportActionBar(view.findViewById(R.id.main_toolbar))

        // Instantiate a ViewPager and a PagerAdapter.
        mPager = view.findViewById(R.id.pager)
        val tablayout: TabLayout = view.findViewById(R.id.tab_layout)

        mPagerAdapter = ScreenSlidePagerAdapter(supportFragmentManager, lifecycle, this)

        /* Toolbar and slider should have the same elevation */
        disableToolbarElevation()

        val minimalUi = GlobalPreferences.getMinimalUi()
        val compactLandscape = resources.configuration.screenHeightDp < 480
        if (isAndroidTV || minimalUi || compactLandscape)
            view.findViewById<android.view.View>(R.id.vpn_dashboard).visibility = android.view.View.GONE
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
        val intent = intent
        if (intent != null) {
            val action = intent.action
            if (Intent.ACTION_VIEW == action) {
                val uri = intent.data
                uri?.let { checkUriForProfileImport(it) }
            }
            val page = intent.getStringExtra("PAGE")
            if ("graph" == page) {
                mPager.currentItem = 1
            }
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
        if (!GlobalPreferences.getMinimalUi())
            menuInflater.inflate(R.menu.main_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.show_log) {
            val showLog = Intent(this, LogWindow::class.java)
            startActivity(showLog)
        }
        return super.onOptionsItemSelected(item)
    }
}