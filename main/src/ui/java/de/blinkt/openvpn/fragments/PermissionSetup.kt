package de.blinkt.openvpn.fragments

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import de.blinkt.openvpn.R
import de.blinkt.openvpn.activities.MainActivity

class PermissionSetup : Fragment(R.layout.permission_setup) {
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.finish_setup).setOnClickListener { (requireActivity() as MainActivity).finishSetup() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val cards = view?.findViewById<LinearLayout>(R.id.permission_cards) ?: return
        cards.removeAllViews()
        val context = requireContext()
        fun add(title: Int, body: Int, granted: Boolean, request: () -> Unit) {
            val row = layoutInflater.inflate(R.layout.permission_card, cards, false)
            row.findViewById<TextView>(R.id.permission_title).setText(title)
            row.findViewById<TextView>(R.id.permission_body).setText(body)
            row.findViewById<MaterialButton>(R.id.permission_button).apply {
                setText(if (granted) R.string.setup_allowed else R.string.setup_allow)
                isEnabled = !granted
                contentDescription = "${getString(title)}, $text"
                setOnClickListener { request() }
            }
            cards.addView(row)
        }
        val prepare = VpnService.prepare(context)
        add(R.string.setup_vpn, R.string.setup_vpn_body, prepare == null) {
            try {
                if (prepare != null) vpnConsent.launch(prepare)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.setup_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        fun runtime(name: String, title: Int, body: Int) {
            add(title, body, context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) { permission.launch(name) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            runtime(Manifest.permission.POST_NOTIFICATIONS, R.string.setup_notify, R.string.setup_notify_body)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN)
            runtime(Manifest.permission.ACCESS_LOCAL_NETWORK, R.string.setup_lan, R.string.setup_lan_body)
    }
}
