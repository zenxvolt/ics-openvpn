package de.blinkt.openvpn.fragments

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import de.blinkt.openvpn.HomeProfileSelection
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.activities.DisconnectVPN
import de.blinkt.openvpn.activities.MainActivity
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.PasswordDialogFragment
import de.blinkt.openvpn.core.VpnStatus

class HomeFragment : Fragment(R.layout.vpn_home), VpnStatus.StateListener {
    private var profiles = emptyList<VpnProfile>()
    private var choice: String? = null
    private var level = ConnectionStatus.LEVEL_NOTCONNECTED
    private var detail = ""
    private var inputRequest: Intent? = null
    private var wave: ObjectAnimator? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.power_button).setOnClickListener { toggle() }
        view.findViewById<View>(R.id.home_open_profiles).setOnClickListener { openProfiles() }
        val halo = view.findViewById<View>(R.id.power_wave)
        wave = ObjectAnimator.ofPropertyValuesHolder(halo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, .76f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, .76f, 1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, .30f)).apply {
            duration = 1500
            interpolator = DecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProfiles()
        VpnStatus.addStateListener(this)
    }

    private fun refreshProfiles() {
        val context = requireContext()
        profiles = HomeProfileSelection.profiles(context)
        choice = HomeProfileSelection.choice(context, profiles)
        val picker = requireView().findViewById<MaterialAutoCompleteTextView>(R.id.profile_picker)
        val entries = listOf(getString(R.string.profile_random)) + profiles.map { it.name }
        picker.setSimpleItems(entries.toTypedArray())
        val selected = profiles.find { it.uuidString == choice }
        picker.setText(selected?.name ?: if (choice == HomeProfileSelection.RANDOM) entries.first() else "", false)
        picker.setOnItemClickListener { _, _, position, _ ->
            val newChoice = if (position == 0) HomeProfileSelection.RANDOM else profiles[position - 1].uuidString
            HomeProfileSelection.save(context, newChoice)
            choice = newChoice
            render()
        }
        render()
    }

    private fun openProfiles() = (requireActivity() as MainActivity).display(R.id.go_profiles)

    private fun toggle() {
        val request = inputRequest
        if (level == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT && request != null) {
            if (request.hasExtra(OpenVPNService.EXTRA_CHALLENGE_TXT))
                PasswordDialogFragment.newInstance(request, false)?.show(parentFragmentManager, "home_challenge")
            else if (request.hasExtra(LaunchVPN.EXTRA_KEY)) {
                startActivity(Intent(requireContext(), LaunchVPN::class.java).apply {
                    action = Intent.ACTION_MAIN
                    putExtra(LaunchVPN.EXTRA_KEY, request.getStringExtra(LaunchVPN.EXTRA_KEY))
                    putExtra(LaunchVPN.EXTRA_HIDELOG, true)
                })
            } else if (request.resolveActivity(requireContext().packageManager) != null) {
                startActivity(request)
            }
            return
        }
        if (VpnStatus.isVPNActive()) {
            startActivity(Intent(requireContext(), DisconnectVPN::class.java))
            return
        }
        val profile = HomeProfileSelection.resolve(requireContext(), profiles) ?: return openProfiles()
        startActivity(Intent(requireContext(), LaunchVPN::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(LaunchVPN.EXTRA_KEY, profile.uuidString)
            putExtra(OpenVPNService.EXTRA_START_REASON, "Home")
        })
    }

    override fun updateState(state: String?, message: String?, resId: Int, level: ConnectionStatus, intent: Intent?) {
        activity?.runOnUiThread {
            if (!isResumed || view == null) return@runOnUiThread
            this.level = level
            detail = VpnStatus.getLastCleanLogMessage(requireContext(), true)
            inputRequest = intent
            render()
        }
    }

    override fun setConnectedVPN(uuid: String?) {
        activity?.runOnUiThread { if (isResumed) render() }
    }

    private fun render() {
        val root = view ?: return
        val active = VpnStatus.isVPNActive()
        val empty = profiles.isEmpty()
        val pending = level == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT && inputRequest != null
        val actionText = when {
            pending -> R.string.home_continue_auth
            active -> R.string.home_disconnect
            empty -> R.string.home_empty
            else -> R.string.home_connect
        }
        val selected = profiles.find { it.uuidString == choice }
        val running = profiles.find { it.uuidString == VpnStatus.getLastConnectedVPNProfile() }
        root.findViewById<TextView>(R.id.home_profile).text = when {
            active -> running?.name ?: getString(R.string.brand_short)
            empty -> getString(R.string.profile_empty_title)
            selected != null -> selected.name
            else -> getString(R.string.profile_random)
        }
        root.findViewById<TextView>(R.id.home_state).text =
            if (level == ConnectionStatus.LEVEL_NOTCONNECTED || detail.isBlank()) getString(R.string.home_inactive) else detail
        root.findViewById<TextView>(R.id.home_action).setText(actionText)
        root.findViewById<MaterialButton>(R.id.power_button).apply {
            isEnabled = active || !empty
            contentDescription = getString(actionText)
        }
        root.findViewById<View>(R.id.home_open_profiles).isVisible = empty
        root.findViewById<TextInputLayout>(R.id.profile_field_layout).apply {
            isVisible = !empty
            isEnabled = !active
            helperText = if (!active && choice == HomeProfileSelection.RANDOM) getString(R.string.home_random_hint) else null
        }
        val animate = isResumed && (active || !empty) &&
            (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
        if (animate && wave?.isStarted == false) wave?.start()
        if (!animate) stopWave()
    }

    private fun stopWave() {
        wave?.cancel()
        view?.findViewById<View>(R.id.power_wave)?.apply { alpha = 1f; scaleX = .88f; scaleY = .88f }
    }

    override fun onPause() {
        VpnStatus.removeStateListener(this)
        stopWave()
        super.onPause()
    }

    override fun onDestroyView() {
        wave = null
        super.onDestroyView()
    }
}
