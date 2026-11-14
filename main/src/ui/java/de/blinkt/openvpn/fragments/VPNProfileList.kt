/*
 * Copyright (c) 2012-2019 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */
package de.blinkt.openvpn.fragments

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.ListFragment
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.activities.ConfigConverter
import de.blinkt.openvpn.activities.DisconnectVPN
import de.blinkt.openvpn.activities.FileSelect
import de.blinkt.openvpn.activities.VPNPreferences
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.PasswordDialogFragment.Companion.newInstance
import de.blinkt.openvpn.core.Preferences
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.core.VpnStatus.StateListener
import de.blinkt.openvpn.fragments.ImportRemoteConfig.Companion.newInstance
import de.blinkt.openvpn.fragments.Utils.alwaysUseOldFileChooser
import de.blinkt.openvpn.fragments.Utils.getWarningText
import java.util.LinkedList
import java.util.TreeSet
import kotlin.math.min

class VPNProfileList : ListFragment(), StateListener {
    protected var mEditProfile: VpnProfile? = null
    private var mLastStatusMessage: String? = null
    private var mArrayadapter: ArrayAdapter<VpnProfile>? = null
    private var defaultVPN: VpnProfile? = null
    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus?,
        intent: Intent?
    ) {
        activity?.runOnUiThread {
            if (!isResumed || view == null) return@runOnUiThread
            mLastStatusMessage = VpnStatus.getLastCleanLogMessage(getActivity())
            mArrayadapter!!.notifyDataSetChanged()
            showUserRequestDialogIfNeeded(level, intent)
        }
    }

    private fun showUserRequestDialogIfNeeded(level: ConnectionStatus?, intent: Intent?): Boolean {
        if (level == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT) {
            if (intent != null && intent.getStringExtra(OpenVPNService.EXTRA_CHALLENGE_TXT) != null) {
                val pwInputFrag = newInstance(intent, false)

                if (pwInputFrag != null && !parentFragmentManager.isStateSaved &&
                    parentFragmentManager.findFragmentByTag("dialog") == null) {
                    pwInputFrag.show(parentFragmentManager, "dialog")
                    return true
                }
            }
        }
        return false
    }

    override fun setConnectedVPN(uuid: String?) {
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        setListAdapter()
    }

    @RequiresApi(api = Build.VERSION_CODES.N_MR1)
    fun updateDynamicShortcuts() {
        val versionExtras = PersistableBundle()
        versionExtras.putInt("version", SHORTCUT_VERSION)

        val shortcutManager =
            getContext()!!.getSystemService<ShortcutManager>(ShortcutManager::class.java)
        if (shortcutManager.isRateLimitingActive()) return

        val shortcuts = shortcutManager.getDynamicShortcuts()
        var maxvpn = shortcutManager.getMaxShortcutCountPerActivity() - 1


        val disconnectShortcut = ShortcutInfo.Builder(getContext(), "disconnectVPN")
            .setShortLabel("Disconnect")
            .setLongLabel("Disconnect VPN")
            .setIntent(
                Intent(
                    getContext(),
                    DisconnectVPN::class.java
                ).setAction(OpenVPNService.DISCONNECT_VPN)
            )
            .setIcon(Icon.createWithResource(getContext(), R.drawable.ic_shortcut_cancel))
            .setExtras(versionExtras)
            .build()

        val newShortcuts = LinkedList<ShortcutInfo>()
        val updateShortcuts = LinkedList<ShortcutInfo>()

        val removeShortcuts = LinkedList<String>()
        val disableShortcuts = LinkedList<String>()

        var addDisconnect = true


        val sortedProfilesLRU = TreeSet<VpnProfile?>(VpnProfileLRUComparator())
        val profileManager = ProfileManager.getInstance(getContext())
        sortedProfilesLRU.addAll(profileManager.getProfiles())

        val LRUProfiles = LinkedList<VpnProfile>()
        maxvpn = min(maxvpn, sortedProfilesLRU.size)

        for (i in 0..<maxvpn) {
            LRUProfiles.add(sortedProfilesLRU.pollFirst()!!)
        }

        for (shortcut in shortcuts) {
            if (shortcut.getId() == "disconnectVPN") {
                addDisconnect = false
                if (shortcut.getExtras() == null
                    || shortcut.getExtras()!!.getInt("version") != SHORTCUT_VERSION
                ) updateShortcuts.add(disconnectShortcut)
            } else {
                val p = ProfileManager.get(getContext(), shortcut.getId())
                if (p == null || p.profileDeleted) {
                    if (shortcut.isEnabled()) {
                        disableShortcuts.add(shortcut.getId())
                        removeShortcuts.add(shortcut.getId())
                    }
                    if (!shortcut.isPinned()) removeShortcuts.add(shortcut.getId())
                } else {
                    if (LRUProfiles.contains(p)) LRUProfiles.remove(p)
                    else removeShortcuts.add(p.getUUIDString())

                    if ((p.getName() != shortcut.getShortLabel()) || shortcut.getExtras() == null || shortcut.getExtras()!!
                            .getInt("version") != SHORTCUT_VERSION
                    ) updateShortcuts.add(createShortcut(p))
                }
            }
        }
        if (addDisconnect) newShortcuts.add(disconnectShortcut)
        for (p in LRUProfiles) newShortcuts.add(createShortcut(p))

        if (updateShortcuts.size > 0) shortcutManager.updateShortcuts(updateShortcuts)
        if (removeShortcuts.size > 0) shortcutManager.removeDynamicShortcuts(removeShortcuts)
        if (newShortcuts.size > 0) shortcutManager.addDynamicShortcuts(newShortcuts)
        if (disableShortcuts.size > 0) shortcutManager.disableShortcuts(
            disableShortcuts,
            "VpnProfile does not exist anymore."
        )
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    fun createShortcut(profile: VpnProfile): ShortcutInfo {
        val shortcutIntent = Intent(Intent.ACTION_MAIN)
        shortcutIntent.setClass(requireContext(), LaunchVPN::class.java)
        shortcutIntent.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString())
        shortcutIntent.setAction(Intent.ACTION_MAIN)
        shortcutIntent.putExtra(OpenVPNService.EXTRA_START_REASON, "shortcut")
        shortcutIntent.putExtra("EXTRA_HIDELOG", true)

        val versionExtras = PersistableBundle()
        versionExtras.putInt("version", SHORTCUT_VERSION)

        return ShortcutInfo.Builder(getContext(), profile.getUUIDString())
            .setShortLabel(profile.getName())
            .setLongLabel(getString(R.string.qs_connect, profile.getName()))
            .setIcon(Icon.createWithResource(getContext(), R.drawable.ic_shortcut_vpn_key))
            .setIntent(shortcutIntent)
            .setExtras(versionExtras)
            .build()
    }

    override fun onResume() {
        super.onResume()
        setListAdapter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            updateDynamicShortcuts()
        }
        defaultVPN = ProfileManager.getAlwaysOnVPN(requireContext())
        VpnStatus.addStateListener(this)
    }

    override fun onPause() {
        super.onPause()
        VpnStatus.removeStateListener(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.vpn_profile_list, container, false)
    }

    private fun setListAdapter() {
        if (mArrayadapter == null) {
            mArrayadapter =
                VPNArrayAdapter(getActivity()!!, R.layout.vpn_list_item, R.id.vpn_item_title)
        }
        populateVpnList()
    }

    private fun populateVpnList() {
        val sortByLRU = Preferences.getDefaultSharedPreferences(requireActivity()).getBoolean(
            PREF_SORT_BY_LRU, false
        )
        this.pM.refreshVPNList(requireContext())
        val allvpn: MutableCollection<VpnProfile?>? = this.pM.getProfiles()
        val sortedset: TreeSet<VpnProfile?>?
        if (sortByLRU) sortedset = TreeSet<VpnProfile?>(VpnProfileLRUComparator())
        else sortedset = TreeSet<VpnProfile?>(VpnProfileNameComparator())

        sortedset.addAll(allvpn!!)
        mArrayadapter!!.clear()
        mArrayadapter!!.addAll(sortedset)

        setListAdapter(mArrayadapter)
        mArrayadapter!!.notifyDataSetChanged()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.profile_actions, menu)

        menu.add(0, MENU_CHANGE_SORTING, 0, R.string.change_sorting)
            .setIcon(R.drawable.ic_sort)
            .setAlphabeticShortcut('s')
            .setTitleCondensed(getString(R.string.sort))
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER)

        menu.add(0, MENU_IMPORT_AS, 0, R.string.import_from_as)
            .setIcon(R.drawable.ic_menu_import_download)
            .setAlphabeticShortcut('p')
            .setTitleCondensed("Import AS")
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_NEVER)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId = item.getItemId()
        if (itemId == R.id.profile_add) {
            onAddOrDuplicateProfile(null)
            return true
        } else if (itemId == R.id.profile_import) {
            return startImportConfigFilePicker()
        } else if (itemId == MENU_CHANGE_SORTING) {
            return changeSorting()
        } else if (itemId == MENU_IMPORT_AS) {
            return startASProfileImport()
        } else {
            return super.onOptionsItemSelected(item)
        }
    }

    private fun startASProfileImport(): Boolean {
        val asImportFrag = newInstance(null)
        asImportFrag.show(getParentFragmentManager(), "dialog")
        return true
    }

    private fun changeSorting(): Boolean {
        val prefs = Preferences.getDefaultSharedPreferences(requireActivity())
        val oldValue = prefs.getBoolean(PREF_SORT_BY_LRU, false)
        val prefsedit = prefs.edit()
        if (oldValue) {
            Toast.makeText(getActivity(), R.string.sorted_az, Toast.LENGTH_SHORT).show()
            prefsedit.putBoolean(PREF_SORT_BY_LRU, false)
        } else {
            prefsedit.putBoolean(PREF_SORT_BY_LRU, true)
            Toast.makeText(getActivity(), R.string.sorted_lru, Toast.LENGTH_SHORT).show()
        }
        prefsedit.apply()
        populateVpnList()
        return true
    }

    private fun startImportConfigFilePicker(): Boolean {
        var startOldFileDialog = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && !alwaysUseOldFileChooser(
                getActivity()
            )
        ) startOldFileDialog = !startFilePicker()

        if (startOldFileDialog) startImportConfig()

        return true
    }

    private fun startFilePicker(): Boolean {
        val i = Utils.getFilePickerIntent(getActivity()!!, Utils.FileType.OVPN_CONFIG)
        if (i != null) {
            startActivityForResult(i, FILE_PICKER_RESULT_KITKAT)
            return true
        } else return false
    }

    private fun startImportConfig() {
        val intent = Intent(getActivity(), FileSelect::class.java)
        intent.putExtra(FileSelect.NO_INLINE_SELECTION, true)
        intent.putExtra(FileSelect.WINDOW_TITLE, R.string.import_configuration_file)
        startActivityForResult(intent, SELECT_PROFILE)
    }

    private fun onAddOrDuplicateProfile(mCopyProfile: VpnProfile?) {
        val context: Context? = getActivity()
        if (context != null) {
            val entry = EditText(context)
            entry.setSingleLine()
            entry.setContentDescription(getString(R.string.name_of_the_vpn_profile))

            val dialog = MaterialAlertDialogBuilder(context)
            if (mCopyProfile == null) dialog.setTitle(R.string.menu_add_profile)
            else {
                dialog.setTitle(
                    context.getString(
                        R.string.duplicate_profile_title,
                        mCopyProfile.mName
                    )
                )
                entry.setText(getString(R.string.copy_of_profile, mCopyProfile.mName))
            }

            dialog.setMessage(R.string.add_profile_name_prompt)
            dialog.setView(entry)

            dialog.setNeutralButton(
                R.string.menu_import_short
            ) { dialog1: DialogInterface?, which: Int -> startImportConfigFilePicker() }
            dialog.setPositiveButton(
                android.R.string.ok
            ) { dialog12: DialogInterface?, which: Int ->
                val name = entry.getText().toString()
                if (this.pM.getProfileByName(name) == null) {
                    val profile: VpnProfile
                    if (mCopyProfile != null) {
                        profile = mCopyProfile.copy(name)
                        // Remove restrictions on copy profile
                        profile.mProfileCreator = null
                        profile.mUserEditable = true
                    } else profile = VpnProfile(name)

                    addProfile(profile)
                    editVPN(profile)
                } else {
                    Toast.makeText(
                        getActivity(),
                        R.string.duplicate_profile_name,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            dialog.setNegativeButton(android.R.string.cancel, null)
            dialog.create().show()
        }
    }

    private fun addProfile(profile: VpnProfile) {
        this.pM.addProfile(profile)
        this.pM.saveProfileList(getActivity())
        profile.addChangeLogEntry("empty profile added via main profile list")
        ProfileManager.saveProfile(getActivity(), profile)
        mArrayadapter!!.add(profile)
    }

    private val pM: ProfileManager
        get() = ProfileManager.getInstance(getActivity())

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_VPN_DELETED) {
            if (mArrayadapter != null && mEditProfile != null) mArrayadapter!!.remove(mEditProfile)
        } else if (resultCode == RESULT_VPN_DUPLICATE && data != null) {
            val profileUUID = data.getStringExtra(VpnProfile.EXTRA_PROFILEUUID)
            val profile = ProfileManager.get(getActivity(), profileUUID)
            if (profile != null) onAddOrDuplicateProfile(profile)
        }

        if (resultCode != Activity.RESULT_OK) return

        if (requestCode == EDIT_VPN_CONFIG) {
            val configuredVPN = data!!.getStringExtra(VpnProfile.EXTRA_PROFILEUUID)

            val profile = ProfileManager.get(getActivity(), configuredVPN)
            profile.addChangeLogEntry("Profile edited by user")
            ProfileManager.saveProfile(getActivity(), profile)
            // Name could be modified, reset List adapter
            setListAdapter()
        } else if (requestCode == SELECT_PROFILE) {
            val fileData = data!!.getStringExtra(FileSelect.RESULT_DATA)
            val uri = Uri.Builder().path(fileData).scheme("file").build()

            startConfigImport(uri)
        } else if (requestCode == IMPORT_PROFILE) {
            val profileUUID = data!!.getStringExtra(VpnProfile.EXTRA_PROFILEUUID)
            mArrayadapter!!.add(ProfileManager.get(getActivity(), profileUUID))
        } else if (requestCode == FILE_PICKER_RESULT_KITKAT) {
            if (data != null) {
                val uri = data.getData()
                startConfigImport(uri)
            }
        }
    }

    private fun startConfigImport(uri: Uri?) {
        val startImport = Intent(getActivity(), ConfigConverter::class.java)
        startImport.setAction(ConfigConverter.IMPORT_PROFILE)
        startImport.setData(uri)
        startActivityForResult(startImport, IMPORT_PROFILE)
    }

    private fun editVPN(profile: VpnProfile) {
        mEditProfile = profile
        val vprefintent = Intent(getActivity(), VPNPreferences::class.java)
            .putExtra(
                VpnProfile.EXTRA_PROFILEUUID,
                profile.getUUID().toString()
            )

        startActivityForResult(vprefintent, EDIT_VPN_CONFIG)
    }

    internal class VpnProfileNameComparator : Comparator<VpnProfile?> {
        override fun compare(lhs: VpnProfile?, rhs: VpnProfile?): Int {
            if (lhs === rhs)  // Catches also both null
                return 0

            if (lhs == null) return -1
            if (rhs == null) return 1

            if (lhs.mName == null) return -1
            if (rhs.mName == null) return 1

            return lhs.mName.compareTo(rhs.mName)
        }
    }

    internal class VpnProfileLRUComparator : Comparator<VpnProfile?> {
        var nameComparator: VpnProfileNameComparator = VpnProfileNameComparator()

        override fun compare(lhs: VpnProfile?, rhs: VpnProfile?): Int {
            if (lhs === rhs)  // Catches also both null
                return 0

            if (lhs == null) return -1
            if (rhs == null) return 1

            // Copied from Long.compare
            if (lhs.mLastUsed > rhs.mLastUsed) return -1
            if (lhs.mLastUsed < rhs.mLastUsed) return 1
            else return nameComparator.compare(lhs, rhs)
        }
    }

    private inner class VPNArrayAdapter(
        context: Context, resource: Int,
        textViewResourceId: Int
    ) : ArrayAdapter<VpnProfile>(context, resource, textViewResourceId) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent)

            val profile = getListAdapter()!!.getItem(position) as VpnProfile

            v.setOnClickListener { editVPN(profile) }
            val subtitle = v.findViewById<TextView>(R.id.vpn_item_subtitle)
            val warningText = getWarningText(requireContext(), profile)

            if (profile.uuidString == defaultVPN?.uuidString) {
                if (warningText.length > 0) warningText.append(" ")
                warningText.append(getString(R.string.profile_default_label))
            }

            if (VpnStatus.isVPNActive() && profile.getUUIDString() == VpnStatus.getLastConnectedVPNProfile()) {
                subtitle.setText(mLastStatusMessage)
                subtitle.setVisibility(View.VISIBLE)
            } else {
                subtitle.setText(warningText)
                if (warningText.length > 0) subtitle.setVisibility(View.VISIBLE)
                else subtitle.setVisibility(View.GONE)
            }


            return v
        }
    }

    companion object {
        val RESULT_VPN_DELETED: Int = Activity.RESULT_FIRST_USER
        val RESULT_VPN_DUPLICATE: Int = Activity.RESULT_FIRST_USER + 1

        // Shortcut version is increased to refresh all shortcuts
        const val SHORTCUT_VERSION: Int = 1
        private const val EDIT_VPN_CONFIG = 92
        private const val SELECT_PROFILE = 43
        private const val IMPORT_PROFILE = 231
        private const val FILE_PICKER_RESULT_KITKAT = 392
        private val MENU_CHANGE_SORTING = Menu.FIRST + 2
        private val MENU_IMPORT_AS = Menu.FIRST + 3
        private const val PREF_SORT_BY_LRU = "sortProfilesByLRU"
    }
}
