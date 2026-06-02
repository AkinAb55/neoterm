package com.termux.x11;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.system.Os;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

/**
 * NeoTerm-owned host for the embedded X server. Declared with
 * android:process=":x11server", so it runs in its own process — mirroring
 * Termux:X11's dedicated server process, which keeps NeoTerm's (and the GUI's)
 * main thread free.
 *
 * It is a BOUND service with no notification of its own: NeoTermService (the
 * app's foreground service) binds it with BIND_IMPORTANT, which raises this
 * process to the client's priority and keeps it alive, while the single status
 * notification stays in NeoTermService. Being an AMS-managed bound service (not
 * a forked child) it isn't subject to phantom-process killing.
 *
 * The X server reads its config from environment variables (per-process), so
 * NeoTerm passes TMPDIR (the proot-shared socket dir) and XKB_CONFIG_ROOT (the
 * distro's xkb data) as bind-intent extras, applied here before start.
 */
@Keep
public class NeoX11Service extends Service {
    public static final String EXTRA_TMPDIR = "tmpdir";
    public static final String EXTRA_XKB = "xkb_config_root";

    private final IBinder binder = new Binder();
    private boolean started = false;
    @SuppressWarnings("FieldCanBeLocal")
    private PowerManager.WakeLock wakeLock = null;

    @SuppressLint("WakelockTimeout")
    private void ensureStarted(Intent intent) {
        if (started) return;
        started = true;
        try {
            if (intent != null) {
                String tmp = intent.getStringExtra(EXTRA_TMPDIR);
                String xkb = intent.getStringExtra(EXTRA_XKB);
                if (tmp != null) trySetenv("TMPDIR", tmp);
                if (xkb != null) trySetenv("XKB_CONFIG_ROOT", xkb);
            }
            // Keep the CPU awake so the X server keeps serving with the screen off.
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeoTerm:x11");
            wakeLock.acquire();

            System.loadLibrary("Xlorie");
            CmdEntryPoint.startInProcess(getApplicationContext(), new String[]{":0"});
            Log.i("NeoX11Service", "in-process X server requested on :0");
        } catch (Throwable t) {
            Log.e("NeoX11Service", "failed to start X server", t);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        ensureStarted(intent);
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Also support being started directly (e.g. on older flows); binding is
        // the normal path.
        ensureStarted(intent);
        return START_NOT_STICKY;
    }

    private void trySetenv(String key, String value) {
        try {
            Os.setenv(key, value, true);
        } catch (Throwable t) {
            Log.e("NeoX11Service", "setenv " + key + " failed", t);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Throwable ignored) {}
        }
        // This is a dedicated process; kill it so the native X server actually
        // exits (its threads would otherwise keep running until the process dies).
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
