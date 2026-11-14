package pe.jhon.mimusica;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** Revisa en GitHub si hay una versión nueva de la app y ayuda a instalarla. */
public final class Updater {
    private static final String API = "https://api.github.com/repos/AndersonBailon27/mi-musica/releases/latest";
    private static final long EVERY_MS = 3 * 60 * 60 * 1000L; // revisa como máximo cada 3 horas
    private static final int THEME = android.R.style.Theme_Material_Dialog_Alert;

    private final Activity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long downloadId = -1;
    private BroadcastReceiver receiver;

    public Updater(Activity act) { this.act = act; }

    public static int installedVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : versionCodeOld(pi);
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("deprecation")
    private static int versionCodeOld(PackageInfo pi) { return pi.versionCode; }

    public static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Revisión automática al abrir la app (silenciosa si no hay nada nuevo). */
    public void checkQuietly() {
        SharedPreferences p = act.getSharedPreferences("ajustes", Context.MODE_PRIVATE);
        long last = p.getLong("ultima_revision", 0);
        if (System.currentTimeMillis() - last < EVERY_MS) return;
        p.edit().putLong("ultima_revision", System.currentTimeMillis()).apply();
        check(false);
    }

    /** Revisión pedida por el usuario: siempre responde algo. */
    public void checkNow() {
        Toast.makeText(act, "Buscando actualizaciones…", Toast.LENGTH_SHORT).show();
        check(true);
    }

    private void check(boolean manual) {
        new Thread(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(API).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                con.setRequestProperty("Accept", "application/vnd.github+json");
                con.setRequestProperty("User-Agent", "MiMusica-Android");
                int code = con.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                }
                JSONObject rel = new JSONObject(sb.toString());
                String tag = rel.optString("tag_name", "");
                int remote = parseVersion(tag);
                String apkUrl = null;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                final String notes = cleanNotes(rel.optString("body", ""));
                final String url = apkUrl;
                final String name = tag.startsWith("v") ? tag.substring(1) : tag;
                final int current = installedVersion(act);
                main.post(() -> {
                    if (act.isFinishing()) return;
                    if (remote > current && url != null) {
                        offer(name, notes, url);
                    } else if (manual) {
                        Toast.makeText(act, "Ya tienes la última versión (" + installedName(act) + ")", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                if (manual) main.post(() -> Toast.makeText(act,
                        "No se pudo revisar. Verifica tu conexión a internet.", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static int parseVersion(String tag) {
        try {
            int dot = tag.lastIndexOf('.');
            return Integer.parseInt(tag.substring(dot + 1).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Deja solo el texto útil de las novedades (sin firmas técnicas). */
    private static String cleanNotes(String body) {
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("Co-Authored-By") || l.startsWith("Claude-Session")) continue;
            l = l.replace("**", "");
            if (out.length() > 0) out.append('\n');
            out.append(l);
        }
        return out.toString();
    }

    private void offer(String name, String notes, String url) {
        String msg = "Hay una versión nueva de Mi Música: " + name + " (tienes la " + installedName(act) + ").";
        if (!notes.isEmpty()) msg += "\n\nNovedades:\n" + notes;
        msg += "\n\nSe instala encima: no pierdes tus carpetas ni ajustes.";
        new AlertDialog.Builder(act, THEME)
                .setTitle("Actualización disponible")
                .setMessage(msg)
                .setPositiveButton("Actualizar", (d, w) -> download(url, name))
                .setNegativeButton("Más tarde", null)
                .show();
    }

    private void download(String url, String name) {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(act, THEME)
                    .setTitle("Un permiso más")
                    .setMessage("Para instalar la actualización, activa \"Permitir de esta fuente\" para Mi Música y luego vuelve y toca Actualizar otra vez.")
                    .setPositiveButton("Abrir ajustes", (d, w) -> {
                        try {
                            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName())));
                        } catch (Exception ignored) {}
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        try {
            DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
                    .setTitle("Mi Música " + name)
                    .setDescription("Descargando actualización")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(act, Environment.DIRECTORY_DOWNLOADS, "MiMusica-" + name + ".apk");
            downloadId = dm.enqueue(req);
            listen(dm);
            Toast.makeText(act, "Descargando la actualización…", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            // Si falla el descargador, se abre el enlace en el navegador
            try { act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
        }
    }

    private void listen(DownloadManager dm) {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;
                stop();
                Uri apk = dm.getUriForDownloadedFile(id);
                if (apk == null) {
                    Toast.makeText(act, "La descarga falló. Inténtalo otra vez.", Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    Intent install = new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(apk, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(install);
                } catch (Exception e) {
                    Toast.makeText(act, "Abre la descarga desde las notificaciones para instalar.", Toast.LENGTH_LONG).show();
                }
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) act.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else act.registerReceiver(receiver, f);
    }

    public void stop() {
        if (receiver != null) {
            try { act.unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiver = null;
        }
    }
}
