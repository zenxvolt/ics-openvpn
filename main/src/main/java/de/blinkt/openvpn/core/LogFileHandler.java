/*
 * Copyright (c) 2012-2015 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Locale;

import de.blinkt.openvpn.R;

/**
 * Created by arne on 23.01.16.
 */
class LogFileHandler extends Handler {
    static final int TRIM_LOG_FILE = 100;
    static final int FLUSH_TO_DISK = 101;
    static final int LOG_INIT = 102;
    public static final int LOG_MESSAGE = 103;
    public static final int MAGIC_BYTE = 0x55;
    protected OutputStream mLogFile;
    private boolean mCacheNeedsRewrite;

    public static final String LOGFILE_NAME = "logcache.dat";


    public LogFileHandler(Looper looper) {
        super(looper);
    }


    @Override
    public void handleMessage(Message msg) {
        try {
            if (msg.what == LOG_INIT) {
                if (mLogFile != null)
                    throw new RuntimeException("mLogFile not null");
                readLogCache((File) msg.obj);
                openLogFile((File) msg.obj);
                if (mCacheNeedsRewrite) {
                    trimLogFile();
                    for (LogItem item : VpnStatus.getlogbuffer())
                        writeLogItemToDisk(item);
                }

            } else if (msg.what == LOG_MESSAGE && msg.obj instanceof LogItem) {
                // Ignore log messages if not yet initialized
                if (mLogFile == null)
                    return;
                writeLogItemToDisk((LogItem) msg.obj);
            } else if (msg.what == TRIM_LOG_FILE) {
                if (mLogFile == null) return;
                trimLogFile();
                for (LogItem li : VpnStatus.getlogbuffer())
                    writeLogItemToDisk(li);
            } else if (msg.what == FLUSH_TO_DISK) {
                flushToDisk();
            }

        } catch (IOException | BufferOverflowException e) {
            // Do not report a failed log write back into the same writer forever.
            android.util.Log.e("OpenVPN", "Error during log cache: " + msg.what, e);
            if (mLogFile != null) {
                try { mLogFile.close(); } catch (IOException ignored) { }
                mLogFile = null;
            }
        }

    }

    private void flushToDisk() throws IOException {
        if (mLogFile != null)
            mLogFile.flush();
    }

    private void trimLogFile() throws IOException {
        mLogFile.flush();
        ((FileOutputStream) mLogFile).getChannel().truncate(0).position(0);
    }

    private void writeLogItemToDisk(LogItem li) throws IOException {

        // We do not really care if the log cache breaks between Android upgrades,
        // write binary format to disc

        byte[] liBytes = li.getMarshaledBytes();

        writeEscapedBytes(liBytes);
    }

    public void writeEscapedBytes(byte[] bytes) throws IOException {
        int magic = 0;
        for (byte b : bytes)
            if (b == MAGIC_BYTE || b == MAGIC_BYTE + 1)
                magic++;

        byte eBytes[] = new byte[bytes.length + magic];

        int i = 0;
        for (byte b : bytes) {
            if (b == MAGIC_BYTE || b == MAGIC_BYTE + 1) {
                eBytes[i++] = MAGIC_BYTE + 1;
                eBytes[i++] = (byte) (b - MAGIC_BYTE);
            } else {
                eBytes[i++] = b;
            }
        }

        byte[] lenBytes = ByteBuffer.allocate(4).putInt(bytes.length).array();
        synchronized (mLogFile) {
            mLogFile.write(MAGIC_BYTE);
            mLogFile.write(lenBytes);
            mLogFile.write(eBytes);
        }
    }

    private void openLogFile(File cacheDir) throws FileNotFoundException {
        File logfile = new File(cacheDir, LOGFILE_NAME);
        mLogFile = new FileOutputStream(logfile, true);
    }

    private void readLogCache(File cacheDir) {
        FileInputStream log = null;
        try {
            File logfile = new File(cacheDir, LOGFILE_NAME);

            if (!logfile.exists() || !logfile.canRead())
                return;

            log = new FileInputStream(logfile);
            readCacheContents(log);
            log.close();

        } catch (java.io.IOException | java.lang.RuntimeException e) {
            mCacheNeedsRewrite = true;
            VpnStatus.logError("Reading cached logfile failed");
            VpnStatus.logException(e);
            e.printStackTrace();
            // ignore reading file error
        } finally {
            synchronized (VpnStatus.readFileLock) {
                VpnStatus.readFileLog = true;
                VpnStatus.readFileLock.notifyAll();
            }
            if (log != null) {
                try {
                    log.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }


    protected void readCacheContents(InputStream in) throws IOException {
        java.io.DataInputStream input = new java.io.DataInputStream(new BufferedInputStream(in));
        int itemsRead = 0;
        while (itemsRead < 2 * VpnStatus.MAXLOGENTRIES) {
            int marker = input.read();
            if (marker == -1) break;
            if (marker != MAGIC_BYTE) continue;
            int len;
            try {
                len = input.readInt();
            } catch (java.io.EOFException truncatedHeader) {
                throw new java.io.EOFException("Truncated cached log header");
            }
            if (len <= 0 || len > 16384)
                throw new IOException("Invalid cached log length: " + len);
            byte[] bytes = new byte[len];
            for (int pos = 0; pos < len; pos++) {
                int value = input.read();
                if (value == -1) throw new java.io.EOFException("Truncated cached log");
                if (value == MAGIC_BYTE)
                    throw new IOException("Unescaped log marker");
                if (value == MAGIC_BYTE + 1) {
                    int escaped = input.read();
                    if (escaped < 0) throw new java.io.EOFException("Truncated log escape");
                    if (escaped > 1) throw new IOException("Invalid log escape");
                    value = MAGIC_BYTE + escaped;
                }
                bytes[pos] = (byte) value;
            }
            restoreLogItem(bytes, len);
            itemsRead++;
        }
        if (itemsRead == 2 * VpnStatus.MAXLOGENTRIES)
            mCacheNeedsRewrite = true;
        VpnStatus.logDebug(R.string.reread_log, itemsRead);
    }

    protected void restoreLogItem(byte[] buf, int len) throws UnsupportedEncodingException {

        LogItem li = new LogItem(buf, len);
        if (li.verify()) {
            VpnStatus.newLogItem(li, true, true);
        } else {
            VpnStatus.logError(String.format(Locale.getDefault(),
                    "Could not read log item from file: %d: %s",
                    len, bytesToHex(buf, Math.max(len, 80))));
        }
    }

    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes, int len) {
        len = Math.min(bytes.length, len);
        char[] hexChars = new char[len * 2];
        for (int j = 0; j < len; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }


}
