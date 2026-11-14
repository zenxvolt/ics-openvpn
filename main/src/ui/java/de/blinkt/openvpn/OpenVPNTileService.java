package de.blinkt.openvpn;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import java.util.List;

import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.GlobalPreferences;
import de.blinkt.openvpn.core.IOpenVPNServiceInternal;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.VPNLaunchHelper;
import de.blinkt.openvpn.core.VpnStatus;

/** Runs beside OpenVPNService so a cold tile sees the tunnel's current state. */
@TargetApi(Build.VERSION_CODES.N)
public class OpenVPNTileService extends TileService implements VpnStatus.StateListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean listening;
    private boolean actionPending;
    private final Runnable resetPending = () -> actionPending = false;
    private List<VpnProfile> profiles = java.util.Collections.emptyList();

    @Override
    public void onStartListening() {
        super.onStartListening();
        listening = true;
        profiles = HomeProfileSelection.INSTANCE.profiles(this);
        VpnStatus.addStateListener(this);
    }

    @Override
    public void onClick() {
        super.onClick();
        if (isLocked()) unlockAndRun(this::toggle);
        else toggle();
    }

    private void toggle() {
        if (actionPending) return;
        if (VpnStatus.isVPNActive()) {
            if (GlobalPreferences.getForceConnected()) {
                toast(R.string.tile_forced);
                return;
            }
            requestStop();
            return;
        }

        profiles = HomeProfileSelection.INSTANCE.profiles(this);
        VpnProfile profile = HomeProfileSelection.INSTANCE.resolve(this, profiles);
        if (profile == null) {
            toast(R.string.novpn_selected);
        } else if (profile.checkProfile(this) != R.string.no_error_found) {
            toast(R.string.config_error_found);
        } else if (VpnService.prepare(this) != null || profile.needUserPWInput(null, null) != 0) {
            markPending();
            toast(R.string.tile_confirm);
            Intent launch = new Intent(this, LaunchVPN.class).setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(LaunchVPN.EXTRA_KEY, profile.getUUIDString())
                    .putExtra(LaunchVPN.EXTRA_HIDELOG, true)
                    .putExtra(OpenVPNService.EXTRA_START_REASON, "QuickTile");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent pending = PendingIntent.getActivity(this, 85, launch,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                startActivityAndCollapse(pending);
            } else {
                startActivityAndCollapse(launch);
            }
        } else {
            markPending();
            try {
                VPNLaunchHelper.startOpenVpn(profile, this, "QuickTile", true);
                Toast.makeText(this, getString(R.string.tile_connecting, profile.getName()), Toast.LENGTH_SHORT).show();
            } catch (RuntimeException error) {
                resetPending.run();
                VpnStatus.logException(error);
                toast(R.string.tile_start_failed);
            }
        }
    }

    private void markPending() {
        actionPending = true;
        main.removeCallbacks(resetPending);
        main.postDelayed(resetPending, 1500);
    }

    private void requestStop() {
        markPending();
        Intent intent = new Intent(this, OpenVPNService.class).setAction(OpenVPNService.START_SERVICE);
        boolean bound = bindService(intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    boolean sent = IOpenVPNServiceInternal.Stub.asInterface(binder).stopVPN(false);
                    toast(sent ? R.string.tile_stopping : R.string.tile_stop_failed);
                } catch (RemoteException error) {
                    VpnStatus.logException(error);
                    toast(R.string.tile_stop_failed);
                } finally {
                    unbindService(this);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {}
        }, Context.BIND_AUTO_CREATE);
        if (!bound) {
            resetPending.run();
            toast(R.string.tile_stop_failed);
        }
    }

    private void toast(int message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void updateState(String state, String message, int resId, ConnectionStatus level, Intent intent) {
        main.post(this::render);
    }

    @Override
    public void setConnectedVPN(String uuid) {
        main.post(this::render);
    }

    private void render() {
        if (!listening) return;
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean active = VpnStatus.isVPNActive();
        String choice = HomeProfileSelection.INSTANCE.choice(this, profiles);
        String uuid = active ? VpnStatus.getLastConnectedVPNProfile() : choice;
        String name = getString(R.string.brand_short);
        if (HomeProfileSelection.RANDOM.equals(uuid)) name = getString(R.string.profile_random);
        for (VpnProfile profile : profiles) {
            if (profile.getUUIDString().equals(uuid)) {
                name = profile.getName();
                break;
            }
        }
        String status = VpnStatus.getLastCleanLogMessage(this, true);
        if (status.isEmpty()) status = getString(R.string.home_inactive);
        String label = active ? getString(R.string.qs_disconnect, name)
                : choice == null ? getString(R.string.novpn_selected) : getString(R.string.qs_connect, name);
        tile.setLabel(label);
        tile.setState(active ? Tile.STATE_ACTIVE : choice == null ? Tile.STATE_UNAVAILABLE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.setSubtitle(status);
        tile.setContentDescription(label + ", " + status);
        tile.updateTile();
    }

    @Override
    public void onStopListening() {
        listening = false;
        VpnStatus.removeStateListener(this);
        super.onStopListening();
    }
}
