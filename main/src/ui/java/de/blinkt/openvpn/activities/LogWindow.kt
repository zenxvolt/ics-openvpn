package de.blinkt.openvpn.activities

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import de.blinkt.openvpn.R
import de.blinkt.openvpn.fragments.GraphFragment
import de.blinkt.openvpn.fragments.LogFragment

class LogWindow : BaseActivity() {
    private lateinit var tabs: TabLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.log_window)
        applyShellInsets(R.id.diagnostics_root)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.shell_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.diagnostics_title)
        tabs = findViewById(R.id.diagnostics_tabs)
        tabs.addTab(tabs.newTab().setText(R.string.section_log))
        tabs.addTab(tabs.newTab().setText(R.string.graph))
        val graph = if (savedInstanceState == null) intent.getStringExtra("PAGE") == "graph"
            else supportFragmentManager.findFragmentById(R.id.container) is GraphFragment
        tabs.getTabAt(if (graph) 1 else 0)?.select()
        showSection(graph)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showSection(tab.position == 1)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun showSection(graph: Boolean) {
        val current = supportFragmentManager.findFragmentById(R.id.container)
        if ((graph && current is GraphFragment) || (!graph && current is LogFragment)) return
        supportFragmentManager.beginTransaction().setReorderingAllowed(true)
            .replace(R.id.container, if (graph) GraphFragment() else LogFragment()).commitNow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabs.getTabAt(if (intent.getStringExtra("PAGE") == "graph") 1 else 0)?.select()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
