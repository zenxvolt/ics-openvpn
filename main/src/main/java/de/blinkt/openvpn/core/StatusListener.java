/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.RequiresApi;

import de.blinkt.openvpn.BuildConfig;
import de.blinkt.openvpn.core.VpnStatus.LogLevel;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Created by arne on 09.11.16.
 */

public class StatusListener implements VpnStatus.LogListener, VpnStatus.ProfileNotifyListener {
    private final java.util.concurrent.ExecutorService mWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler mMainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final StatusServiceConnection mConnection = new StatusServiceConnection();
    private StatusCallback mCallback;
    private File mCacheDir;

    // A fresh callback per binding prevents late events from a dead service from
    // overwriting a new connection. Gate each event under the same status lock.
    private final class StatusCallback extends IStatusCallbacks.Stub {
        boolean discardSnapshot;

        @Override
        public void logBufferCleared() {
            synchronized (VpnStatus.class) {
                if (mCallback != this) return;
                discardSnapshot = true;
                VpnStatus.clearLogLocal();
            }
        }

        @Override
        public void newLogItem(LogItem item) {
            synchronized (VpnStatus.class) {
                if (mCallback == this) VpnStatus.newLogItem(item);
            }
        }

        @Override
        public void updateStateString(String state, String msg, int resid,
                                      ConnectionStatus level, Intent intent) {
            synchronized (VpnStatus.class) {
                if (mCallback != this) return;
                VpnStatus.setStatusAvailable(true);
                VpnStatus.updateStateString(state, msg, resid, level, reCreateIntent(intent));
            }
        }

        @Override
        public void updateByteCount(long inBytes, long outBytes) {
            synchronized (VpnStatus.class) {
                if (mCallback == this) VpnStatus.updateByteCount(inBytes, outBytes);
            }
        }

        @Override
        public void connectedVPN(String uuid) {
            synchronized (VpnStatus.class) {
                if (mCallback == this) VpnStatus.setConnectedVPNProfile(uuid);
            }
        }

        @Override
        public void notifyProfileVersionChanged(String uuid, int version) {
            synchronized (VpnStatus.class) {
                if (mCallback == this)
                    ProfileManager.notifyProfileVersionChanged(mContext, uuid, version);
            }
        }
    }

    private Intent reCreateIntent(Intent intent) {
        if (intent == null) return null;
        Intent copy = new Intent(intent.getAction(), intent.getData());
        if (intent.getComponent() != null && mContext.getPackageName().equals(intent.getComponent().getPackageName()))
            copy.setComponent(intent.getComponent());
        if (intent.getExtras() != null) copy.putExtras(intent.getExtras());
        return copy;
    }

    final class StatusServiceConnection implements ServiceConnection {
        public volatile IServiceStatus serviceStatus;
        private ParcelFileDescriptor snapshotPipe;
        private int retryCount;

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            final IServiceStatus remote = IServiceStatus.Stub.asInterface(binder);
            serviceStatus = remote;
            if (binder.queryLocalInterface("de.blinkt.openvpn.core.IServiceStatus") != null) {
                VpnStatus.initLogCache(mCacheDir);
                if (BuildConfig.DEBUG || BuildConfig.FLAVOR.equals("skeleton"))
                    VpnStatus.addLogListener(StatusListener.this);
                return;
            }
            final StatusCallback callback = new StatusCallback();
            synchronized (VpnStatus.class) {
                mCallback = callback;
                VpnStatus.setStatusAvailable(false);
            }
            mWorker.execute(() -> {
                boolean registered = false;
                try {
                    TrafficHistory history = remote.getTrafficHistory();
                    synchronized (VpnStatus.class) {
                        if (mCallback != callback) return;
                        VpnStatus.setTrafficHistory(history);
                    }
                    ParcelFileDescriptor pipe = remote.registerStatusCallback(callback);
                    registered = true;
                    synchronized (VpnStatus.class) {
                        if (mCallback != callback) {
                            pipe.close();
                            return;
                        }
                        snapshotPipe = pipe;
                    }
                    synchronized (VpnStatus.class) {
                        if (mCallback == callback) {
                            VpnStatus.addProfileStateListener(StatusListener.this);
                            VpnStatus.setLogClearHandler(() -> mWorker.execute(() -> {
                                try { remote.clearLog(); }
                                catch (RemoteException e) { VpnStatus.logException(e); }
                            }));
                        }
                    }
                    try (DataInputStream input = new DataInputStream(
                            new ParcelFileDescriptor.AutoCloseInputStream(pipe))) {
                        while (true) {
                            int len = input.readUnsignedShort();
                            if (len == 0x7fff) break;
                            if (len == 0 || len > 16384)
                                throw new IOException("Invalid log snapshot length: " + len);
                            byte[] bytes = new byte[len];
                            input.readFully(bytes);
                            LogItem item = new LogItem(bytes, len);
                            if (!item.verify()) throw new IOException("Invalid log snapshot item");
                            synchronized (VpnStatus.class) {
                                if (mCallback != callback) return;
                                // Rebinding can replay history; equality includes timestamp.
                                if (!callback.discardSnapshot)
                                    VpnStatus.newLogItemIfUnique(item);
                            }
                        }
                    }
                    synchronized (VpnStatus.class) {
                        if (mCallback == callback) {
                            snapshotPipe = null;
                            retryCount = 0;
                        }
                    }
                } catch (RemoteException | IOException | RuntimeException e) {
                    android.util.Log.e("OpenVPN", "Unable to synchronize VPN status", e);
                    if (registered) {
                        try { remote.unregisterStatusCallback(callback); }
                        catch (RemoteException ignored) { }
                    }
                    mMainHandler.post(() -> {
                        synchronized (VpnStatus.class) {
                            if (mCallback != callback) return;
                        }
                        disconnected();
                        scheduleRebind();
                    });
                } finally {
                    boolean stale;
                    synchronized (VpnStatus.class) { stale = mCallback != callback; }
                    if (registered && stale) {
                        try { remote.unregisterStatusCallback(callback); }
                        catch (RemoteException ignored) { }
                    }
                }
            });
        }

        private void disconnected() {
            synchronized (VpnStatus.class) {
                mCallback = null;
                serviceStatus = null;
                VpnStatus.setLogClearHandler(null);
                if (snapshotPipe != null) {
                    try { snapshotPipe.close(); } catch (IOException ignored) { }
                    snapshotPipe = null;
                }
                VpnStatus.setStatusAvailable(false);
            }
            VpnStatus.removeLogListener(StatusListener.this);
            VpnStatus.removeProfileStateListener(StatusListener.this);
        }

        private final Runnable rebind = () -> {
            mContext.unbindService(this);
            bindStatusService();
        };

        private void scheduleRebind() {
            mMainHandler.removeCallbacks(rebind);
            long delay = Math.min(30000L, 1000L << Math.min(retryCount++, 5));
            mMainHandler.postDelayed(rebind, delay);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            disconnected();
            // Android reconnects an existing binding after a service process crash.
        }

        @Override
        public void onBindingDied(ComponentName name) {
            disconnected();
            scheduleRebind();
        }
    }

    private void bindStatusService() {
        Intent intent = new Intent(mContext, OpenVPNStatusService.class);
        intent.setAction(OpenVPNService.START_SERVICE);
        mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    private Context mContext;
    private String pkgName = "(packageName not yet set)";

    void init(Context c) {
        pkgName = c.getPackageName();
        mCacheDir = c.getCacheDir();
        this.mContext = c;
        bindStatusService();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            logLatestExitReasons(c);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private void logLatestExitReasons(Context c) {
        ActivityManager activityManager = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        List<ApplicationExitInfo> exitReasons = activityManager.getHistoricalProcessExitReasons(null, 0, 5);
        ApplicationExitInfo lastguiexit = null;
        ApplicationExitInfo lastserviceexit = null;
        for (ApplicationExitInfo aei : exitReasons) {
            if (aei.getProcessName().endsWith(":openvpn")) {
                if (lastserviceexit == null || aei.getTimestamp() > lastserviceexit.getTimestamp())
                    lastserviceexit = aei;
            } else {
                if (lastguiexit == null || aei.getTimestamp() > lastguiexit.getTimestamp())
                    lastguiexit = aei;
            }
        }
        logExitNotification(lastserviceexit, "Last exit reason reported by Android for Service Process: ");
        logExitNotification(lastguiexit, "Last exit reason reported by Android for UI Process: ");

    }

    private void logExitNotification(ApplicationExitInfo aei, String s) {
        if (aei != null) {
            LogItem li = new LogItem(LogLevel.DEBUG, s + aei, aei.getTimestamp());
            VpnStatus.newLogItemIfUnique(li);
        }
    }

    @Override
    public void newLog(LogItem logItem) {
        String tag = pkgName + "(OpenVPN)";
        long logAge = System.currentTimeMillis() - logItem.getLogtime();
        if (logAge > 5000)
        {
            tag += String.format(Locale.US, "[%ds ago]", logAge/1000 );
        }

        switch (logItem.getLogLevel()) {
            case INFO -> Log.i(tag, logItem.getString(mContext));
            case DEBUG -> Log.d(tag, logItem.getString(mContext));
            case ERROR -> Log.e(tag, logItem.getString(mContext));
            case VERBOSE -> Log.v(tag, logItem.getString(mContext));
            default -> Log.w(tag, logItem.getString(mContext));
        }

    }

    @Override
    public void notifyProfileVersionChanged(String uuid, int version, boolean changedInThisProcess) {
        if(changedInThisProcess && mConnection.serviceStatus != null)
        {
            try {
                mConnection.serviceStatus.notifyProfileVersionChanged(uuid, version);
            } catch (RemoteException e) {
                VpnStatus.logException(e);
            }
        }
    }
}
