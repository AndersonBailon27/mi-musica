package pe.jhon.mimusica;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reproduce la música en segundo plano, con notificación y controles de pantalla de bloqueo. */
public class MusicService extends Service implements
        MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener {

    public static final String ACT_TOGGLE = "pe.jhon.mimusica.TOGGLE";
    public static final String ACT_NEXT = "pe.jhon.mimusica.NEXT";
    public static final String ACT_PREV = "pe.jhon.mimusica.PREV";
    public static final String ACT_CLOSE = "pe.jhon.mimusica.CLOSE";
    private static final String CHANNEL = "reproduccion";
    private static final int NOTIF_ID = 1;

    public interface Listener { void onPlayerChanged(); }

    public class LocalBinder extends Binder {
        public MusicService get() { return MusicService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final List<Listener> listeners = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaPlayer mp;
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focusRequest;
    private NotificationManager nm;
    private SharedPreferences prefs;
    private AudioAttributes attrs;

    private List<Song> queue = new ArrayList<>();
    private List<Integer> order = new ArrayList<>();
    private int pos = -1;
    private boolean prepared = false;
    private boolean playWhenReady = false;
    private boolean pausedByFocus = false;
    private boolean foreground = false;
    private boolean shuffle = false;
    private int repeat = 0; // 0 = no, 1 = todas, 2 = una
    private int errorStreak = 0;
    private Bitmap currentArt;

    private boolean noisyRegistered = false;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // Se desconectaron los audífonos: pausar
            Toast.makeText(MusicService.this, "Pausado: se desconectaron los audífonos", Toast.LENGTH_SHORT).show();
            pause();
        }
    };

    // ---------------- Ciclo de vida ----------------

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        prefs = getSharedPreferences("ajustes", MODE_PRIVATE);
        shuffle = prefs.getBoolean("aleatorio", false);
        repeat = prefs.getInt("repetir", 0);
        attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }

        session = new MediaSession(this, "MiMusica");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setSessionActivity(contentIntent());
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onSkipToNext() { next(false); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long p) { seekTo((int) p); }
        });
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACT_TOGGLE: toggle(); break;
                case ACT_NEXT: next(false); break;
                case ACT_PREV: prev(); break;
                case ACT_CLOSE:
                    pause();
                    removeNotification();
                    stopSelf();
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!isPlaying()) {
            removeNotification();
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        unregisterNoisy();
        if (mp != null) { try { mp.release(); } catch (Exception ignored) {} mp = null; }
        abandonFocus();
        if (session != null) session.release();
        removeNotification();
        super.onDestroy();
    }

    // ---------------- API para la pantalla ----------------

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        for (Listener l : new ArrayList<>(listeners)) l.onPlayerChanged();
    }

    public Song getCurrentSong() {
        if (pos < 0 || pos >= order.size()) return null;
        int q = order.get(pos);
        return q >= 0 && q < queue.size() ? queue.get(q) : null;
    }

    public boolean isPlaying() {
        try { return mp != null && prepared && mp.isPlaying(); } catch (Exception e) { return false; }
    }

    public boolean isShuffle() { return shuffle; }
    public int getRepeat() { return repeat; }

    public int getPosition() {
        try { return (mp != null && prepared) ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    public int getDuration() {
        try {
            if (mp != null && prepared) return mp.getDuration();
        } catch (Exception ignored) {}
        Song s = getCurrentSong();
        return s != null ? (int) s.duration : 0;
    }

    public void playList(List<Song> list, int index) {
        if (list == null || list.isEmpty()) return;
        queue = new ArrayList<>(list);
        buildOrder(index);
        playCurrent();
    }

    public void toggle() {
        if (isPlaying()) pause(); else play();
    }

    public void play() {
        if (getCurrentSong() == null) return;
        if (!prepared) {
            playWhenReady = true;
            if (mp == null) playCurrent();
            return;
        }
        startPlayback();
    }

    public void pause() {
        pausedByFocus = false;
        pauseInternal();
    }

    public void next(boolean auto) {
        if (order.isEmpty()) return;
        if (auto && repeat == 2 && prepared) {
            mp.seekTo(0);
            mp.start();
            updateState();
            return;
        }
        if (pos >= order.size() - 1) {
            if (auto && repeat == 0) {
                Toast.makeText(this, "Terminó la lista. Activa \"Repetir\" para que siga sonando.", Toast.LENGTH_LONG).show();
                pauseInternal();
                if (prepared) mp.seekTo(0);
                updateState();
                changed();
                return;
            }
            if (shuffle) Collections.shuffle(order);
            pos = 0;
        } else {
            pos++;
        }
        playCurrent();
    }

    public void prev() {
        if (order.isEmpty()) return;
        if (getPosition() > 3000) {
            seekTo(0);
            return;
        }
        pos = pos <= 0 ? order.size() - 1 : pos - 1;
        playCurrent();
    }

    public void seekTo(int ms) {
        if (mp != null && prepared) {
            mp.seekTo(ms);
            updateState();
        }
    }

    public void toggleShuffle() {
        int current = pos >= 0 && pos < order.size() ? order.get(pos) : 0;
        shuffle = !shuffle;
        prefs.edit().putBoolean("aleatorio", shuffle).apply();
        if (!queue.isEmpty()) buildOrder(current);
        changed();
    }

    public void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        prefs.edit().putInt("repetir", repeat).apply();
        changed();
    }

    // ---------------- Lógica interna ----------------

    private void buildOrder(int startIndex) {
        order = new ArrayList<>();
        for (int i = 0; i < queue.size(); i++) order.add(i);
        if (startIndex < 0 || startIndex >= queue.size()) startIndex = 0;
        if (shuffle) {
            Collections.shuffle(order);
            order.remove(Integer.valueOf(startIndex));
            order.add(0, startIndex);
            pos = 0;
        } else {
            pos = startIndex;
        }
    }

    private void playCurrent() {
        final Song s = getCurrentSong();
        if (s == null) return;
        prepared = false;
        playWhenReady = true;
        if (mp == null) {
            mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnPreparedListener(this);
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
        } else {
            try { mp.reset(); } catch (Exception ignored) {}
        }
        currentArt = null;
        try {
            mp.setDataSource(getApplicationContext(), s.uri());
            mp.prepareAsync();
        } catch (Exception e) {
            onError(mp, 0, 0);
            return;
        }
        updateMetadata(s);
        updateState();
        if (foreground) showNotification(true);
        changed();

        new Thread(() -> {
            Bitmap big = null;
            try { big = ArtLoader.loadSync(getApplicationContext(), s); } catch (Throwable ignored) {}
            final Bitmap b = ArtLoader.shrink(big, 256);
            main.post(() -> {
                if (getCurrentSong() == s) {
                    currentArt = b;
                    updateMetadata(s);
                    showNotification(isPlaying() || playWhenReady);
                }
            });
        }).start();
    }

    private void startPlayback() {
        if (!requestFocus()) {
            Toast.makeText(this, "Otra app está usando el audio", Toast.LENGTH_SHORT).show();
            return;
        }
        try { mp.setVolume(1f, 1f); mp.start(); } catch (Exception e) { return; }
        registerNoisy();
        session.setActive(true);
        updateState();
        showNotification(true);
        changed();
    }

    private void pauseInternal() {
        playWhenReady = false;
        try { if (mp != null && prepared && mp.isPlaying()) mp.pause(); } catch (Exception ignored) {}
        unregisterNoisy();
        updateState();
        if (getCurrentSong() != null) showNotification(false);
        changed();
    }

    @Override
    public void onPrepared(MediaPlayer player) {
        prepared = true;
        errorStreak = 0;
        if (playWhenReady) startPlayback();
        else { updateState(); changed(); }
    }

    @Override
    public void onCompletion(MediaPlayer player) {
        next(true);
    }

    @Override
    public boolean onError(MediaPlayer player, int what, int extra) {
        prepared = false;
        errorStreak++;
        Song s = getCurrentSong();
        if (s != null) Toast.makeText(this, "No se pudo reproducir: " + s.title, Toast.LENGTH_SHORT).show();
        if (errorStreak < Math.min(5, order.size())) {
            main.postDelayed(() -> {
                if (order.isEmpty()) return;
                pos = (pos + 1) % order.size();
                playCurrent();
            }, 400);
        } else {
            errorStreak = 0;
            playWhenReady = false;
            updateState();
            changed();
        }
        return true;
    }

    // ---------------- Foco de audio y audífonos ----------------

    private boolean requestFocus() {
        int r;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(this)
                        .build();
            }
            r = am.requestAudioFocus(focusRequest);
        } else {
            r = am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
            } else {
                am.abandonAudioFocus(this);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                if (isPlaying()) Toast.makeText(this, "Pausado: otra app empezó a usar el audio", Toast.LENGTH_LONG).show();
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isPlaying()) {
                    pauseInternal();
                    pausedByFocus = true;
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                try { if (mp != null) mp.setVolume(0.3f, 0.3f); } catch (Exception ignored) {}
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                try { if (mp != null) mp.setVolume(1f, 1f); } catch (Exception ignored) {}
                if (pausedByFocus) {
                    pausedByFocus = false;
                    play();
                }
                break;
        }
    }

    private void registerNoisy() {
        if (noisyRegistered) return;
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, f);
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try { unregisterReceiver(noisy); } catch (Exception ignored) {}
        noisyRegistered = false;
    }

    // ---------------- Sesión multimedia y notificación ----------------

    private void updateState() {
        int st;
        if (getCurrentSong() == null) st = PlaybackState.STATE_NONE;
        else if (isPlaying()) st = PlaybackState.STATE_PLAYING;
        else if (playWhenReady && !prepared) st = PlaybackState.STATE_BUFFERING;
        else st = PlaybackState.STATE_PAUSED;
        try { session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP)
                .setState(st, getPosition(), st == PlaybackState.STATE_PLAYING ? 1f : 0f)
                .build()); } catch (Throwable ignored) {}
    }

    private void updateMetadata(Song s) {
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, s.duration);
        if (currentArt != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentArt);
        try {
            session.setMetadata(b.build());
        } catch (Throwable e) {
            // si la carátula no entra, se manda sin carátula
            try { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, null); session.setMetadata(b.build()); } catch (Throwable ignored) {}
        }
    }

    private PendingIntent contentIntent() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent action(String act, int code) {
        Intent i = new Intent(this, MusicService.class).setAction(act);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @SuppressWarnings("deprecation")
    private void showNotification(boolean playing) {
        Song s = getCurrentSong();
        if (s == null) return;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_note)
                .setContentTitle(s.title)
                .setContentText(s.artist)
                .setContentIntent(contentIntent())
                .setDeleteIntent(action(ACT_CLOSE, 4))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOngoing(playing)
                .setColor(0xFF1ED760);
        if (currentArt != null) b.setLargeIcon(currentArt);
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_prev), "Anterior", action(ACT_PREV, 1)).build());
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                playing ? "Pausa" : "Reproducir", action(ACT_TOGGLE, 2)).build());
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_next), "Siguiente", action(ACT_NEXT, 3)).build());
        b.setStyle(new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        Notification n;
        try {
            n = b.build();
        } catch (Throwable e) {
            b.setLargeIcon((Bitmap) null);
            n = b.build();
        }

        if (playing) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIF_ID, n);
                }
                foreground = true;
            } catch (Throwable e) {
                try { nm.notify(NOTIF_ID, n); } catch (Throwable ignored) {}
            }
        } else {
            if (foreground) {
                stopForeground(STOP_FOREGROUND_DETACH);
                foreground = false;
            }
            try { nm.notify(NOTIF_ID, n); } catch (Throwable ignored) {}
        }
    }

    private void removeNotification() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        if (nm != null) nm.cancel(NOTIF_ID);
    }
}
