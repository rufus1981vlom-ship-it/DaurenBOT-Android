package kz.daurenbot.v11p0;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Test-only foreground service. Executes Node as libnode.so in the :engine Android process.
 * No Termux, WebView, browser automation, Internet-exposed ports or external server.
 */
public class EngineService extends Service {
    private static final String CHANNEL = "daurenbot-p0";
    private static final int NOTIFICATION_ID = 1101;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private native int runNode(String root, String privateState, String tempDir);

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "DaurenBOT P0: WhatsApp engine", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("DaurenBOT v11 — тестовое подключение")
                .setContentText("Встроенный Node/Baileys. Автоответы выключены.")
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        } else {
            startForeground(NOTIFICATION_ID, notification());
        }
        if (RUNNING.compareAndSet(false, true)) {
            new Thread(this::launchEngine,"Dauren-Node-VM").start();
        }
        return START_STICKY;
    }

    private void launchEngine() {
        try {
            File project = new File(getFilesDir(), "embedded-node");
            stageAssets(project);
            File state = new File(getNoBackupFilesDir(), "engine-state");
            if (!state.exists() && !state.mkdirs()) throw new IOException("Cannot create state dir");
            File temp = new File(getCacheDir(), "node-tmp");
            if (!temp.exists() && !temp.mkdirs()) throw new IOException("Cannot create temp dir");
            System.loadLibrary("c++_shared");
            System.loadLibrary("node");
            System.loadLibrary("dauren_bridge");
            int exit = runNode(project.getCanonicalPath(), state.getCanonicalPath(), temp.getCanonicalPath());
            writeFailure("Node exited: " + exit);
        } catch (Throwable e) {
            Log.e("DaurenBOT-P0","Failed to start embedded Node",e);
            writeFailure("Node failed: "+e.getClass().getSimpleName()+": "+e.getMessage());
        } finally {
            RUNNING.set(false);
            stopSelf();
        }
    }

    private void stageAssets(File project) throws IOException {
        File marker = new File(project, ".ready-v11-p0");
        if (marker.exists()) return;
        if (!project.exists() && !project.mkdirs()) throw new IOException("Cannot mkdir runtime");
        String root = project.getCanonicalPath() + File.separator;
        try (InputStream input = getAssets().open("runtime.zip");
             ZipInputStream archive = new ZipInputStream(input)) {
            ZipEntry item;
            byte[] buffer = new byte[32 * 1024];
            while ((item = archive.getNextEntry()) != null) {
                File target = new File(project, item.getName());
                String canonical = target.getCanonicalPath();
                if (!canonical.startsWith(root)) throw new SecurityException("Unsafe ZIP path");
                if (item.isDirectory()) {
                    if(!target.exists() && !target.mkdirs()) throw new IOException("Cannot create directory");
                } else {
                    File parent = target.getParentFile();
                    if (!parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create parent");
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        int n;
                        while ((n=archive.read(buffer))!=-1) out.write(buffer,0,n);
                    }
                }
                archive.closeEntry();
            }
        }
        try (FileOutputStream output = new FileOutputStream(marker)) {output.write(49);}
    }

    private void writeFailure(String text) {
        try {
            File root = new File(getNoBackupFilesDir(),"engine-state");
            if (!root.exists()) root.mkdirs();
            try(FileOutputStream out=new FileOutputStream(new File(root,"last-exit.txt"))) {
                out.write(text.getBytes("UTF-8"));
            }
        }catch(Exception ignored){}
    }

    @Override public IBinder onBind(Intent intent){return null;}
}
