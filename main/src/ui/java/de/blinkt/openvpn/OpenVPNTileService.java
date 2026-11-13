/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */
package de.blinkt.openvpn;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.IOpenVPNServiceInternal;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VpnStatus;

@TargetApi(Build.VERSION_CODES.N)
public class OpenVPNTileService extends TileService implements VpnStatus.StateListener {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mListening;
    private boolean mActionPending;
    private boolean mStopBound;
    private final Runnable mRefresh = this::renderTile;
    private final Runnable mActionTimeout = () -> {
        releaseStopBinding();
        mActionPending = false;
        renderTile();
    };

    private final ServiceConnection mStopConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                IOpenVPNServiceInternal service = IOpenVPNServiceInternal.Stub.asInterface(binder);
                if (service != null) service.stopVPN(false);
            } catch (RemoteException e) {
                VpnStatus.logException(e);
                mActionPending = false;
            } finally {
                releaseStopBinding();
                renderTile();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mActionTimeout.run();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            mActionTimeout.run();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            mActionTimeout.run();
        }
    };

    @Override
    public void onClick() {
        super.onClick();
        if (isLocked()) unlockAndRun(this::clickAction);
        else clickAction();
    }

    private void clickAction() {
        // Re-evaluate after unlocking: status may have changed behind the lock screen.
        if (mActionPending || !VpnStatus.isStatusAvailable()) {
            renderTile();
            return;
        }
        if (VpnStatus.isVPNActive()) {
            // Stopping the current VPN must not depend on an always-on/default profile.
            mActionPending = true;
            Intent stop = new Intent(this, OpenVPNService.class).setAction(OpenVPNService.START_SERVICE);
            mStopBound = bindService(stop, mStopConnection, 0);
            if (!mStopBound) mActionPending = false;
        } else {
            VpnProfile profile = getQSVPN();
            if (profile == null) {
                Toast.makeText(this, R.string.novpn_selected, Toast.LENGTH_SHORT).show();
                renderTile();
                return;
            }
            mActionPending = true;
            launchVPN(profile, this);
        }
        mHandler.removeCallbacks(mActionTimeout);
        if (mActionPending) mHandler.postDelayed(mActionTimeout, 10000);
        renderTile();
    }

    @SuppressWarnings("deprecation")
    void launchVPN(VpnProfile profile, Context context) {
        // Go through the same consent/credentials flow as the profile screen.
        Intent launch = new Intent(this, LaunchVPN.class)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(LaunchVPN.EXTRA_KEY, profile.getUUIDString())
                .putExtra(OpenVPNService.EXTRA_START_REASON, "QuickTile")
                .putExtra("EXTRA_HIDELOG", true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pending = PendingIntent.getActivity(this, 0, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pending);
        } else {
            startActivityAndCollapse(launch);
        }
    }

    public VpnProfile getQSVPN() {
        return ProfileManager.getAlwaysOnVPN(this);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        mListening = true;
        VpnStatus.addStateListener(this);
        renderTile();
    }

    private void renderTile() {
        if (!mListening) return;
        Tile tile = getQsTile();
        if (tile == null) return;
        synchronized (VpnStatus.class) {
            boolean ready = VpnStatus.isStatusAvailable();
            boolean running = VpnStatus.isVPNActive();
            ConnectionStatus level = VpnStatus.getLastStateLevel();
            VpnProfile profile = running
                    ? ProfileManager.get(this, VpnStatus.getLastConnectedVPNProfile()) : getQSVPN();
            String name = profile == null ? getString(R.string.app) : profile.getName();
            String status = ready ? VpnStatus.getLastCleanLogMessage(this, true)
                    : getString(R.string.qs_syncing);
            if (status.isEmpty()) status = getString(R.string.state_disconnected);
            tile.setLabel(running ? getString(R.string.qs_disconnect, name)
                    : profile == null ? getString(R.string.novpn_selected)
                    : getString(R.string.qs_connect, name));
            tile.setState(!ready || mActionPending || (!running && profile == null)
                    ? Tile.STATE_UNAVAILABLE
                    : level == ConnectionStatus.LEVEL_CONNECTED ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.setSubtitle(status);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.setStateDescription(status);
            tile.setContentDescription(tile.getLabel() + ", " + status);
            tile.updateTile();
        }
    }

    @Override
    public void updateState(String state, String message, int resId, ConnectionStatus level, Intent intent) {
        mHandler.post(() -> {
            mActionPending = false;
            // Keep the timeout until any outstanding stop binding is released.
            renderTile();
        });
    }

    @Override
    public void setConnectedVPN(String uuid) {
        mHandler.removeCallbacks(mRefresh);
        mHandler.post(mRefresh);
    }

    private void releaseStopBinding() {
        if (mStopBound) {
            mStopBound = false;
            unbindService(mStopConnection);
        }
    }

    @Override
    public void onStopListening() {
        mListening = false;
        VpnStatus.removeStateListener(this);
        mHandler.removeCallbacks(mRefresh);
        super.onStopListening();
    }

    @Override
    public void onDestroy() {
        mListening = false;
        VpnStatus.removeStateListener(this);
        mHandler.removeCallbacksAndMessages(null);
        releaseStopBinding();
        super.onDestroy();
    }
}
