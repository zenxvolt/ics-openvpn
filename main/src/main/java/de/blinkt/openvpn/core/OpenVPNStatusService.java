/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import androidx.annotation.Nullable;
import android.util.Pair;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Ensure to forward message from the backend process :openvpn to the frontend process via AIDL
 */

public class OpenVPNStatusService extends Service implements VpnStatus.LogListener, VpnStatus.ByteCountListener, VpnStatus.StateListener, VpnStatus.ProfileNotifyListener {
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }


    final RemoteCallbackList<IStatusCallbacks> mCallbacks =
            new RemoteCallbackList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler.setService(this);
        VpnStatus.initLogCache(getCacheDir());
        VpnStatus.addLogListener(this);
        VpnStatus.addByteCountListener(this);
        VpnStatus.addStateListener(this);
        VpnStatus.addProfileStateListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        VpnStatus.removeLogListener(this);
        VpnStatus.removeByteCountListener(this);
        VpnStatus.removeStateListener(this);
        VpnStatus.removeProfileStateListener(this);
        mCallbacks.kill();
        mHandler.removeCallbacksAndMessages(null);

    }

    private final IServiceStatus.Stub mBinder = new IServiceStatus.Stub() {

        @Override
        public ParcelFileDescriptor registerStatusCallback(IStatusCallbacks cb) throws RemoteException {
            // The client invokes registration on a worker. Wait for the disk cache before
            // the snapshot, never while holding the VpnStatus lock used by the reader.
            synchronized (VpnStatus.readFileLock) {
                long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
                while (!VpnStatus.readFileLog) {
                    long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                    if (remaining <= 0) break;
                    try {
                        VpnStatus.readFileLock.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RemoteException("Interrupted loading log cache");
                    }
                }
            }
            try {
                final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                final LogItem[] logbuffer;
                try {
                    synchronized (VpnStatus.class) {
                        logbuffer = VpnStatus.getlogbuffer();
                        // All events already in the Handler queue are covered by this
                        // snapshot. The callback cookie prevents replaying them later.
                        mCallbacks.register(cb, mEventSequence);
                        cb.connectedVPN(VpnStatus.getLastConnectedVPNProfile());
                        if (mLastUpdateMessage != null)
                            sendUpdate(cb, mLastUpdateMessage);
                        TrafficHistory.LastDiff traffic = VpnStatus.trafficHistory.getLastDiff(null);
                        cb.updateByteCount(traffic.getIn(), traffic.getOut());
                    }
                } catch (RemoteException | RuntimeException e) {
                    mCallbacks.unregister(cb);
                    pipe[0].close();
                    pipe[1].close();
                    throw e;
                }
                new Thread(() -> {
                    try (DataOutputStream fd = new DataOutputStream(
                            new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]))) {
                        for (LogItem logItem : logbuffer) {
                            byte[] bytes = logItem.getMarshaledBytes();
                            fd.writeShort(bytes.length);
                            fd.write(bytes);
                        }
                        fd.writeShort(0x7fff);
                    } catch (IOException | RuntimeException e) {
                        android.util.Log.w("OpenVPN", "Log snapshot pipe closed", e);
                    }
                }, "OpenVPN-log-snapshot").start();
                return pipe[0];
            } catch (IOException e) {
                throw new RemoteException(e.getMessage());
            }
        }

        @Override
        public void unregisterStatusCallback(IStatusCallbacks cb) throws RemoteException {
            mCallbacks.unregister(cb);
        }

        @Override
        public void clearLog() {
            VpnStatus.clearLog();
            VpnStatus.logInfo(de.blinkt.openvpn.R.string.logCleared);
        }

        @Override
        public String getLastConnectedVPN() throws RemoteException {
            return VpnStatus.getLastConnectedVPNProfile();
        }

        @Override
        public void setCachedPassword(String uuid, int type, String password) {
            PasswordCache.setCachedPassword(uuid, type, password);
        }

        @Override
        public TrafficHistory getTrafficHistory() throws RemoteException {
            return VpnStatus.trafficHistory;
        }

        @Override
        public void notifyProfileVersionChanged(String uuid, int version) throws RemoteException {
            ProfileManager.notifyProfileVersionChanged(OpenVPNStatusService.this, uuid, version);
        }

    };

    @Override
    public void logBufferChanged() {
        enqueue(SEND_LOG_CLEARED, null);
    }

    @Override
    public void newLog(LogItem logItem) {
        enqueue(SEND_NEW_LOGITEM, logItem);
    }

    @Override
    public void updateByteCount(long in, long out, long diffIn, long diffOut) {
        enqueue(SEND_NEW_BYTECOUNT, Pair.create(in, out));
    }

    private UpdateMessage mLastUpdateMessage;
    // Accessed under VpnStatus.class; all listener callbacks are dispatched under it.
    private long mEventSequence;

    private static class SequencedEvent {
        final long sequence;
        final Object payload;
        SequencedEvent(long sequence, Object payload) {
            this.sequence = sequence;
            this.payload = payload;
        }
    }

    private void enqueue(int what, Object payload) {
        synchronized (VpnStatus.class) {
            mHandler.obtainMessage(what, new SequencedEvent(++mEventSequence, payload)).sendToTarget();
        }
    }

    static class UpdateMessage {
        public String state;
        public String logmessage;
        public ConnectionStatus level;
        public Intent intent;
        int resId;

        UpdateMessage(String state, String logmessage, int resId, ConnectionStatus level, Intent intent) {
            this.state = state;
            this.resId = resId;
            this.logmessage = logmessage;
            this.level = level;
            this.intent = intent;
        }
    }


    @Override
    public void updateState(String state, String logmessage, int localizedResId, ConnectionStatus level, Intent intent) {

        mLastUpdateMessage = new UpdateMessage(state, logmessage, localizedResId, level, intent);
        enqueue(SEND_NEW_STATE, mLastUpdateMessage);
    }

    @Override
    public void setConnectedVPN(String uuid) {
        enqueue(SEND_NEW_CONNECTED_VPN, uuid);
    }

    @Override
    public void notifyProfileVersionChanged(String uuid, int version, boolean changedInThisProcess)
    {
        enqueue(SEND_NEW_PROFILE_VERSION, Pair.create(uuid, version));
    }

    private final OpenVPNStatusHandler mHandler = new OpenVPNStatusHandler();

    private static final int SEND_LOG_CLEARED = 105;
    private static final int SEND_NEW_LOGITEM = 100;
    private static final int SEND_NEW_STATE = 101;
    private static final int SEND_NEW_BYTECOUNT = 102;
    private static final int SEND_NEW_CONNECTED_VPN = 103;
    private static final int SEND_NEW_PROFILE_VERSION = 104;

    private static class OpenVPNStatusHandler extends Handler {
        OpenVPNStatusHandler() {
            super(android.os.Looper.getMainLooper());
        }

        WeakReference<OpenVPNStatusService> service = null;

        private void setService(OpenVPNStatusService statusService) {
            service = new WeakReference<>(statusService);
        }

        @Override
        public void handleMessage(Message msg) {

            RemoteCallbackList<IStatusCallbacks> callbacks;
            if (service == null || service.get() == null)
                return;
            callbacks = service.get().mCallbacks;
            // Broadcast to all clients the new value.
            SequencedEvent event = (SequencedEvent) msg.obj;
            final int N = callbacks.beginBroadcast();
            try {
            for (int i = 0; i < N; i++) {
                try {
                    Object cookie = callbacks.getBroadcastCookie(i);
                    if (msg.what != SEND_NEW_PROFILE_VERSION
                            && cookie instanceof Long && event.sequence <= (Long) cookie)
                        continue;
                    IStatusCallbacks broadcastItem = callbacks.getBroadcastItem(i);

                    switch (msg.what) {
                        case SEND_LOG_CLEARED:
                            broadcastItem.logBufferCleared();
                            break;
                        case SEND_NEW_LOGITEM:
                            broadcastItem.newLogItem((LogItem) event.payload);
                            break;
                        case SEND_NEW_BYTECOUNT:
                            Pair<Long, Long> inout = (Pair<Long, Long>) event.payload;
                            broadcastItem.updateByteCount(inout.first, inout.second);
                            break;
                        case SEND_NEW_STATE:
                            sendUpdate(broadcastItem, (UpdateMessage) event.payload);
                            break;

                        case SEND_NEW_CONNECTED_VPN:
                            broadcastItem.connectedVPN((String) event.payload);
                            break;

                        case SEND_NEW_PROFILE_VERSION:
                            Pair<String, Integer> profileupdate =  (Pair<String, Integer>) event.payload;
                            broadcastItem.notifyProfileVersionChanged(profileupdate.first, profileupdate.second);
                    }
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing
                    // the dead object for us.
                }
            }
            } finally {
                callbacks.finishBroadcast();
            }
        }
    }

    private static void sendUpdate(IStatusCallbacks broadcastItem,
                                   UpdateMessage um) throws RemoteException {
        broadcastItem.updateStateString(um.state, um.logmessage, um.resId, um.level, um.intent);
    }
}