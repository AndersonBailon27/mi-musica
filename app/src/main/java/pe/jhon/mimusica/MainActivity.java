package pe.jhon.mimusica;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements MusicService.Listener {

    private static final int REQ_PERMISOS = 7;
    private static final int BG = 0xFF0E0F13, CARD = 0xFF171920, CARD2 = 0xFF1F222B,
            TXT = 0xFFF2F3F7, MUT = 0xFF8B90A0, ACC = 0xFF1ED760, LINE = 0xFF262A35;

    private final List<Song> all = new ArrayList<>();
    private final List<Song> shown = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MusicService svc;
    private boolean bound = false;
    private boolean loaded = false;
    private boolean askedOnce = false;
    private boolean deniedForever = false;
    private boolean userSeeking = false;
    private int pendingIndex = -1;
    private List<Song> pendingList;

    private SongAdapter adapter;
    private EditText search;
    private TextView count, emptyText, pTitle, pArtist, tCur, tDur, permMsg;
    private ListView list;
    private LinearLayout player, permPanel;
    private Button permBtn;
    private ImageView pArt;
    private ImageButton bPlay, bPrev, bNext, bShuf, bRep;
    private SeekBar seek;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = ((MusicService.LocalBinder) binder).get();
            bound = true;
            svc.addListener(MainActivity.this);
            if (pendingList != null) {
                svc.playList(pendingList, pendingIndex);
                pendingList = null;
            }
            updatePlayer();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            svc = null;
            bound = false;
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (svc != null && svc.getCurrentSong() != null && !userSeeking) {
                int dur = svc.getDuration();
                int p = svc.getPosition();
                seek.setMax(Math.max(dur, 1));
                seek.setProgress(p);
                tCur.setText(Song.fmt(p));
                tDur.setText(Song.fmt(dur));
            }
            handler.postDelayed(this, 500);
        }
    };

    // ---------------- Construcción de la pantalla ----------------

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private ImageButton iconButton(int res, int sizeDp, int color) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(color));
        b.setBackground(null);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(8);
        b.setPadding(pad, pad, pad, pad);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Encabezado
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(16), dp(10), dp(6));
        TextView title = text(24, TXT, true);
        title.setText("Mi Música");
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton refresh = iconButton(R.drawable.ic_refresh, 44, MUT);
        refresh.setOnClickListener(v -> {
            if (hasAudioPermission()) {
                loadSongs(true);
            }
        });
        header.addView(refresh);
        root.addView(header);

        // Buscador
        search = new EditText(this);
        search.setHint("Buscar canción, artista o álbum…");
        search.setHintTextColor(MUT);
        search.setTextColor(TXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setBackground(rounded(CARD, 12, LINE));
        search.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(dp(16), dp(4), dp(16), dp(6));
        root.addView(search, sp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { filter(); }
        });

        count = text(13, MUT, false);
        count.setPadding(dp(18), dp(2), dp(18), dp(6));
        root.addView(count);

        // Contenido: lista / vacío / permiso
        FrameLayout content = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0x22FFFFFF));
        list.setPadding(dp(8), 0, dp(8), dp(8));
        list.setClipToPadding(false);
        list.setFastScrollEnabled(true);
        adapter = new SongAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> playFrom(position));
        content.addView(list);

        emptyText = text(15, MUT, false);
        emptyText.setSingleLine(false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(dp(30), dp(30), dp(30), dp(30));
        emptyText.setVisibility(View.GONE);
        content.addView(emptyText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        permPanel = new LinearLayout(this);
        permPanel.setOrientation(LinearLayout.VERTICAL);
        permPanel.setGravity(Gravity.CENTER);
        permPanel.setPadding(dp(30), dp(30), dp(30), dp(30));
        permPanel.setBackgroundColor(BG);
        ImageView permIcon = new ImageView(this);
        permIcon.setImageResource(R.drawable.ic_note);
        permIcon.setImageTintList(ColorStateList.valueOf(ACC));
        permPanel.addView(permIcon, new LinearLayout.LayoutParams(dp(72), dp(72)));
        permMsg = text(16, TXT, false);
        permMsg.setSingleLine(false);
        permMsg.setGravity(Gravity.CENTER);
        permMsg.setPadding(0, dp(16), 0, dp(20));
        permMsg.setText("Para mostrar tu música, Mi Música necesita permiso para ver los archivos de audio de tu celular.");
        permPanel.addView(permMsg);
        permBtn = new Button(this);
        permBtn.setText("Dar permiso");
        permBtn.setAllCaps(false);
        permBtn.setTextColor(0xFF04130A);
        permBtn.setTextSize(16);
        permBtn.setTypeface(Typeface.DEFAULT_BOLD);
        permBtn.setBackground(rounded(ACC, 30, 0));
        permBtn.setPadding(dp(28), 0, dp(28), 0);
        permBtn.setOnClickListener(v -> {
            if (deniedForever) {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null));
                startActivity(i);
            } else {
                requestPerms();
            }
        });
        permPanel.addView(permBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50)));
        permPanel.setVisibility(View.GONE);
        content.addView(permPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Reproductor inferior
        player = new LinearLayout(this);
        player.setOrientation(LinearLayout.VERTICAL);
        player.setBackgroundColor(CARD);
        player.setPadding(dp(16), dp(12), dp(16), dp(10));
        player.setVisibility(View.GONE);

        LinearLayout now = new LinearLayout(this);
        now.setGravity(Gravity.CENTER_VERTICAL);
        pArt = new ImageView(this);
        pArt.setBackground(rounded(CARD2, 8, 0));
        pArt.setClipToOutline(true);
        now.addView(pArt, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout nowTxt = new LinearLayout(this);
        nowTxt.setOrientation(LinearLayout.VERTICAL);
        nowTxt.setPadding(dp(12), 0, 0, 0);
        pTitle = text(16, TXT, true);
        pArtist = text(13, MUT, false);
        nowTxt.addView(pTitle);
        nowTxt.addView(pArtist);
        now.addView(nowTxt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        player.addView(now);

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        seekRow.setPadding(0, dp(6), 0, 0);
        tCur = text(12, MUT, false);
        tDur = text(12, MUT, false);
        seek = new SeekBar(this);
        seek.setProgressTintList(ColorStateList.valueOf(ACC));
        seek.setThumbTintList(ColorStateList.valueOf(TXT));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF555A66));
        seekRow.addView(tCur, new LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT));
        seekRow.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tDur.setGravity(Gravity.END);
        seekRow.addView(tDur, new LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT));
        player.addView(seekRow);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) tCur.setText(Song.fmt(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (svc != null) svc.seekTo(s.getProgress());
            }
        });

        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setGravity(Gravity.CENTER);
        bShuf = iconButton(R.drawable.ic_shuffle, 46, MUT);
        bPrev = iconButton(R.drawable.ic_prev, 54, TXT);
        bPlay = iconButton(R.drawable.ic_play, 64, 0xFF000000);
        bPlay.setBackground(rounded(TXT, 32, 0));
        bPlay.setPadding(dp(14), dp(14), dp(14), dp(14));
        bNext = iconButton(R.drawable.ic_next, 54, TXT);
        bRep = iconButton(R.drawable.ic_repeat, 46, MUT);
        View[] cs = {bShuf, bPrev, bPlay, bNext, bRep};
        for (View v : cs) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.setMargins(dp(8), 0, dp(8), 0);
            ctrl.addView(v, lp);
        }
        player.addView(ctrl);

        bPlay.setOnClickListener(v -> { if (svc != null) svc.toggle(); });
        bNext.setOnClickListener(v -> { if (svc != null) svc.next(false); });
        bPrev.setOnClickListener(v -> { if (svc != null) svc.prev(); });
        bShuf.setOnClickListener(v -> {
            if (svc == null) return;
            svc.toggleShuffle();
            Toast.makeText(this, svc.isShuffle() ? "Aleatorio activado" : "Aleatorio desactivado", Toast.LENGTH_SHORT).show();
        });
        bRep.setOnClickListener(v -> {
            if (svc == null) return;
            svc.cycleRepeat();
            String[] m = {"Repetir desactivado", "Repetir todas", "Repetir una"};
            Toast.makeText(this, m[svc.getRepeat()], Toast.LENGTH_SHORT).show();
        });

        root.addView(player, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, MusicService.class), conn, BIND_AUTO_CREATE);
        handler.post(tick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasAudioPermission()) {
            permPanel.setVisibility(View.GONE);
            if (!loaded) loadSongs(false);
        } else {
            permPanel.setVisibility(View.VISIBLE);
            if (!askedOnce) {
                askedOnce = true;
                requestPerms();
            }
        }
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);
        if (svc != null) svc.removeListener(this);
        if (bound) {
            unbindService(conn);
            bound = false;
        }
        svc = null;
        super.onStop();
    }

    // ---------------- Permisos ----------------

    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerms() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{audioPermission(), Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISOS);
        } else {
            requestPermissions(new String[]{audioPermission()}, REQ_PERMISOS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMISOS) return;
        if (hasAudioPermission()) {
            permPanel.setVisibility(View.GONE);
            loadSongs(false);
        } else {
            permPanel.setVisibility(View.VISIBLE);
            deniedForever = !shouldShowRequestPermissionRationale(audioPermission());
            if (deniedForever) {
                permMsg.setText("El permiso está bloqueado. Toca \"Abrir ajustes\", entra a Permisos y permite \"Música y audio\" (o \"Archivos\").");
                permBtn.setText("Abrir ajustes");
            } else {
                permBtn.setText("Dar permiso");
            }
        }
    }

    // ---------------- Música ----------------

    private void loadSongs(boolean showToast) {
        new Thread(() -> {
            final List<Song> found = new ArrayList<>();
            String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION
            };
            String sel = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        long id = c.getLong(0);
                        String t = c.getString(1);
                        String a = c.getString(2);
                        String al = c.getString(3);
                        long alId = c.getLong(4);
                        long dur = c.getLong(5);
                        if (dur > 0 && dur < 10000) continue; // ignora sonidos muy cortos
                        if (t == null || t.trim().isEmpty()) t = "Sin título";
                        if (a == null || a.trim().isEmpty() || a.equals("<unknown>")) a = "Artista desconocido";
                        if (al == null || al.equals("<unknown>")) al = "";
                        found.add(new Song(id, t.trim(), a.trim(), al, alId, dur));
                    }
                }
            } catch (Exception ignored) {
            }
            final Collator col = Collator.getInstance(new Locale("es"));
            col.setStrength(Collator.PRIMARY);
            Collections.sort(found, (x, y) -> col.compare(x.title, y.title));
            handler.post(() -> {
                all.clear();
                all.addAll(found);
                loaded = true;
                filter();
                if (showToast) Toast.makeText(this, "Biblioteca actualizada", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void filter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Song s : all) {
            if (q.isEmpty() || (s.title + " " + s.artist + " " + s.album).toLowerCase(Locale.ROOT).contains(q)) shown.add(s);
        }
        adapter.notifyDataSetChanged();
        int n = all.size();
        count.setText(n == 1 ? "1 canción" : n + " canciones");
        if (loaded && all.isEmpty()) {
            emptyText.setText("No se encontraron canciones en tu celular.\n\nAsegúrate de que tus archivos de música (MP3, M4A…) estén en el teléfono o en la memoria SD y toca el botón de actualizar arriba.");
            emptyText.setVisibility(View.VISIBLE);
        } else if (loaded && shown.isEmpty()) {
            emptyText.setText("Ninguna canción coincide con tu búsqueda.");
            emptyText.setVisibility(View.VISIBLE);
        } else {
            emptyText.setVisibility(View.GONE);
        }
    }

    private void playFrom(int position) {
        if (position < 0 || position >= shown.size()) return;
        Intent i = new Intent(this, MusicService.class);
        startService(i);
        List<Song> copy = new ArrayList<>(shown);
        if (svc != null) {
            svc.playList(copy, position);
        } else {
            pendingList = copy;
            pendingIndex = position;
        }
    }

    @Override
    public void onPlayerChanged() {
        updatePlayer();
    }

    private void updatePlayer() {
        if (svc == null) return;
        Song s = svc.getCurrentSong();
        if (s == null) {
            player.setVisibility(View.GONE);
            adapter.notifyDataSetChanged();
            return;
        }
        player.setVisibility(View.VISIBLE);
        pTitle.setText(s.title);
        pArtist.setText(s.artist);
        Object tag = pArt.getTag();
        if (!(tag instanceof Long) || (Long) tag != s.id) ArtLoader.into(this, s, pArt);
        bPlay.setImageResource(svc.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        bShuf.setImageTintList(ColorStateList.valueOf(svc.isShuffle() ? ACC : MUT));
        int r = svc.getRepeat();
        bRep.setImageResource(r == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        bRep.setImageTintList(ColorStateList.valueOf(r == 0 ? MUT : ACC));
        adapter.notifyDataSetChanged();
    }

    // ---------------- Lista ----------------

    private static class Holder {
        ImageView art;
        TextView title, sub;
    }

    private class SongAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return shown.get(p).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                h = new Holder();
                h.art = new ImageView(MainActivity.this);
                h.art.setBackground(rounded(CARD2, 8, 0));
                h.art.setClipToOutline(true);
                row.addView(h.art, new LinearLayout.LayoutParams(dp(48), dp(48)));
                LinearLayout txt = new LinearLayout(MainActivity.this);
                txt.setOrientation(LinearLayout.VERTICAL);
                txt.setPadding(dp(12), 0, 0, 0);
                h.title = text(15, TXT, false);
                h.sub = text(13, MUT, false);
                txt.addView(h.title);
                txt.addView(h.sub);
                row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.setTag(h);
                convertView = row;
            } else {
                h = (Holder) convertView.getTag();
            }
            Song s = shown.get(position);
            Song cur = svc != null ? svc.getCurrentSong() : null;
            boolean on = cur != null && cur.id == s.id;
            h.title.setText(s.title);
            h.title.setTextColor(on ? ACC : TXT);
            h.sub.setText(s.artist + "  ·  " + Song.fmt(s.duration));
            convertView.setBackground(on ? rounded(CARD2, 12, 0) : null);
            ArtLoader.into(MainActivity.this, s, h.art);
            return convertView;
        }
    }
}
