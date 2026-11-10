package pe.jhon.mimusica;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.text.InputType;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements MusicService.Listener {

    private static final int REQ_PERMISOS = 7;
    private static final int BG = 0xFF0E0F13, CARD = 0xFF171920, CARD2 = 0xFF1F222B,
            TXT = 0xFFF2F3F7, MUT = 0xFF8B90A0, ACC = 0xFF1ED760, LINE = 0xFF262A35;
    private static final int DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert;

    // Pantallas
    private static final int MODE_SONGS = 0, MODE_FOLDERS = 1, MODE_FOLDER = 2, MODE_HIDDEN = 3;
    private static final String ROW_NEW_FOLDER = "nueva", ROW_HIDDEN = "ocultas", ROW_ADD_SONGS = "agregar";

    /** Carpetas del sistema que guardan audios que NO son música (notas de voz, grabaciones…). */
    private static final String[] NOT_MUSIC = {
            "/whatsapp", "/telegram", "/messenger/", "/signal/", "/android/media/", "/android/data/",
            "/recordings/", "/recording/", "/recorder/", "/soundrecorder", "/sound recorder",
            "/voice recorder", "/voice notes", "/voicenotes", "/voice/", "/grabaciones",
            "/call/", "/callrecord", "/call recordings", "/call_recordings", "/llamadas/",
            "/notes/", "/ringtones/", "/notifications/", "/alarms/", "/podcasts/", "/audiobooks/",
            "ptt-", "aud-"
    };

    private final List<Song> all = new ArrayList<>();          // toda la música encontrada
    private final Map<Long, Song> byId = new HashMap<>();
    private final List<Object> rows = new ArrayList<>();        // filas visibles (Song, Folder o String)
    private final List<Song> rowSongs = new ArrayList<>();      // solo las canciones visibles, en orden
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Library lib;
    private int mode = MODE_SONGS;
    private Library.Folder openFolder;

    private MusicService svc;
    private boolean bound = false;
    private boolean loaded = false;
    private boolean askedOnce = false;
    private boolean deniedForever = false;
    private boolean userSeeking = false;
    private int pendingIndex = -1;
    private List<Song> pendingList;

    private RowAdapter adapter;
    private EditText search;
    private TextView count, emptyText, pTitle, pArtist, tCur, tDur, permMsg, tabSongs, tabFolders, subTitle;
    private ListView list;
    private LinearLayout player, permPanel, tabs, subBar;
    private ImageButton subMore;
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

    // ---------------- Utilidades de interfaz ----------------

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

    private TextView tab(String label) {
        TextView t = text(14, TXT, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(8), dp(18), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    // ---------------- Pantalla ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lib = new Library(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Encabezado
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(16), dp(10), dp(4));
        TextView title = text(24, TXT, true);
        title.setText("Mi Música");
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton refresh = iconButton(R.drawable.ic_refresh, 44, MUT);
        refresh.setOnClickListener(v -> { if (hasAudioPermission()) loadSongs(true); });
        header.addView(refresh);
        root.addView(header);

        // Pestañas
        tabs = new LinearLayout(this);
        tabs.setPadding(dp(16), dp(4), dp(16), dp(6));
        tabSongs = tab("Canciones");
        tabFolders = tab("Carpetas");
        tabs.addView(tabSongs);
        tabs.addView(tabFolders);
        tabSongs.setOnClickListener(v -> setMode(MODE_SONGS));
        tabFolders.setOnClickListener(v -> setMode(MODE_FOLDERS));
        root.addView(tabs);

        // Barra de carpeta abierta (volver + nombre + opciones)
        subBar = new LinearLayout(this);
        subBar.setGravity(Gravity.CENTER_VERTICAL);
        subBar.setPadding(dp(6), dp(2), dp(6), dp(2));
        ImageButton back = iconButton(R.drawable.ic_back, 44, TXT);
        back.setOnClickListener(v -> setMode(MODE_FOLDERS));
        subBar.addView(back);
        subTitle = text(18, TXT, true);
        subBar.addView(subTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        subMore = iconButton(R.drawable.ic_more, 44, MUT);
        subMore.setOnClickListener(v -> { if (openFolder != null) folderOptions(openFolder); });
        subBar.addView(subMore);
        subBar.setVisibility(View.GONE);
        root.addView(subBar);

        // Buscador
        search = new EditText(this);
        search.setHint("Buscar…");
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
            @Override public void afterTextChanged(Editable e) { refreshRows(); }
        });

        count = text(13, MUT, false);
        count.setPadding(dp(18), dp(2), dp(18), dp(6));
        root.addView(count);

        // Contenido
        FrameLayout content = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0x22FFFFFF));
        list.setPadding(dp(8), 0, dp(8), dp(8));
        list.setClipToPadding(false);
        list.setFastScrollEnabled(true);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onRowClick(position));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Object o = rows.get(position);
            if (o instanceof Song) { songOptions((Song) o); return true; }
            if (o instanceof Library.Folder) { folderOptions((Library.Folder) o); return true; }
            return false;
        });
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
        permPanel.setClickable(true);
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
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            } else {
                requestPerms();
            }
        });
        permPanel.addView(permBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50)));
        permPanel.setVisibility(View.GONE);
        content.addView(permPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        buildPlayer(root);
        setContentView(root);
        paintTabs();
    }

    private void buildPlayer(LinearLayout root) {
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
            toast(svc.isShuffle() ? "Aleatorio activado" : "Aleatorio desactivado");
        });
        bRep.setOnClickListener(v -> {
            if (svc == null) return;
            svc.cycleRepeat();
            String[] m = {"Repetir desactivado", "Repetir todas", "Repetir una"};
            toast(m[svc.getRepeat()]);
        });

        root.addView(player, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (mode == MODE_FOLDER || mode == MODE_HIDDEN) setMode(MODE_FOLDERS);
        else if (mode == MODE_FOLDERS) setMode(MODE_SONGS);
        else super.onBackPressed();
    }

    // ---------------- Navegación ----------------

    private void setMode(int m) {
        mode = m;
        if (m != MODE_FOLDER) openFolder = null;
        search.setText("");
        paintTabs();
        refreshRows();
        list.setSelection(0);
    }

    private void paintTabs() {
        boolean detail = mode == MODE_FOLDER || mode == MODE_HIDDEN;
        tabs.setVisibility(detail ? View.GONE : View.VISIBLE);
        subBar.setVisibility(detail ? View.VISIBLE : View.GONE);
        subMore.setVisibility(mode == MODE_FOLDER ? View.VISIBLE : View.GONE);
        if (mode == MODE_FOLDER && openFolder != null) subTitle.setText(openFolder.name);
        if (mode == MODE_HIDDEN) subTitle.setText("Canciones ocultas");
        boolean songs = mode == MODE_SONGS;
        tabSongs.setBackground(rounded(songs ? ACC : CARD, 20, 0));
        tabSongs.setTextColor(songs ? 0xFF04130A : TXT);
        tabFolders.setBackground(rounded(mode == MODE_FOLDERS ? ACC : CARD, 20, 0));
        tabFolders.setTextColor(mode == MODE_FOLDERS ? 0xFF04130A : TXT);
        search.setHint(mode == MODE_FOLDERS ? "Buscar carpeta…" : "Buscar canción, artista o álbum…");
    }

    private boolean matches(Song s, String q) {
        return q.isEmpty() || (s.title + " " + s.artist + " " + s.album).toLowerCase(Locale.ROOT).contains(q);
    }

    private void refreshRows() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        rows.clear();
        rowSongs.clear();
        String countText = "";
        String emptyMsg = null;

        switch (mode) {
            case MODE_SONGS: {
                int visible = 0;
                for (Song s : all) {
                    if (lib.hidden.contains(s.id)) continue;
                    visible++;
                    if (matches(s, q)) { rows.add(s); rowSongs.add(s); }
                }
                countText = visible == 1 ? "1 canción" : visible + " canciones";
                if (loaded && visible == 0) emptyMsg = "No se encontró música en tu celular.\n\nAsegúrate de que tus canciones (MP3, M4A…) estén en el teléfono o en la memoria SD y toca el botón de actualizar arriba.";
                else if (loaded && rows.isEmpty()) emptyMsg = "Ninguna canción coincide con tu búsqueda.";
                break;
            }
            case MODE_FOLDERS: {
                if (q.isEmpty()) rows.add(ROW_NEW_FOLDER);
                for (Library.Folder f : lib.folders) {
                    if (q.isEmpty() || f.name.toLowerCase(Locale.ROOT).contains(q)) rows.add(f);
                }
                if (q.isEmpty() && hiddenCount() > 0) rows.add(ROW_HIDDEN);
                int n = lib.folders.size();
                countText = n == 1 ? "1 carpeta" : n + " carpetas";
                if (n == 0) countText = "Crea carpetas para ordenar tu música";
                break;
            }
            case MODE_FOLDER: {
                if (openFolder == null) { setMode(MODE_FOLDERS); return; }
                if (q.isEmpty()) rows.add(ROW_ADD_SONGS);
                int n = 0;
                for (Long id : openFolder.ids) {
                    Song s = byId.get(id);
                    if (s == null) continue;
                    n++;
                    if (matches(s, q)) { rows.add(s); rowSongs.add(s); }
                }
                countText = n == 1 ? "1 canción" : n + " canciones";
                if (n == 0) countText = "Carpeta vacía: toca \"Agregar canciones\"";
                break;
            }
            case MODE_HIDDEN: {
                for (Song s : all) {
                    if (lib.hidden.contains(s.id) && matches(s, q)) { rows.add(s); rowSongs.add(s); }
                }
                countText = "Mantén presionada una canción para mostrarla otra vez";
                if (rows.isEmpty()) emptyMsg = "No hay canciones ocultas.";
                break;
            }
        }
        count.setText(countText);
        adapter.notifyDataSetChanged();
        if (emptyMsg != null) {
            emptyText.setText(emptyMsg);
            emptyText.setVisibility(View.VISIBLE);
        } else {
            emptyText.setVisibility(View.GONE);
        }
    }

    private int hiddenCount() {
        int n = 0;
        for (Song s : all) if (lib.hidden.contains(s.id)) n++;
        return n;
    }

    private void onRowClick(int position) {
        if (position < 0 || position >= rows.size()) return;
        Object o = rows.get(position);
        if (o instanceof Song) {
            playFrom(rowSongs.indexOf(o));
        } else if (o instanceof Library.Folder) {
            openFolder = (Library.Folder) o;
            mode = MODE_FOLDER;
            search.setText("");
            paintTabs();
            refreshRows();
            list.setSelection(0);
        } else if (ROW_NEW_FOLDER.equals(o)) {
            askName("Nueva carpeta", "", name -> {
                lib.create(name);
                refreshRows();
                toast("Carpeta \"" + name + "\" creada");
            });
        } else if (ROW_HIDDEN.equals(o)) {
            setMode(MODE_HIDDEN);
        } else if (ROW_ADD_SONGS.equals(o)) {
            pickSongsFor(openFolder);
        }
    }

    // ---------------- Diálogos ----------------

    private interface NameCallback { void onName(String name); }

    private void askName(String title, String initial, NameCallback cb) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        input.setText(initial);
        input.setSelection(initial.length());
        input.setHint("Ej.: Para entrenar, Cumbia, Favoritas…");
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        box.addView(input);
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Guardar", (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (n.isEmpty()) { toast("Escribe un nombre"); return; }
                    cb.onName(n);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void songOptions(Song s) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        labels.add("Agregar a carpeta");
        actions.add(() -> chooseFolderFor(s));
        if (mode == MODE_FOLDER && openFolder != null) {
            final Library.Folder f = openFolder;
            labels.add("Quitar de esta carpeta");
            actions.add(() -> { lib.remove(f, s.id); refreshRows(); toast("Quitada de \"" + f.name + "\""); });
        }
        if (mode == MODE_HIDDEN) {
            labels.add("Mostrar otra vez en Canciones");
            actions.add(() -> { lib.unhide(s.id); refreshRows(); toast("\"" + s.title + "\" vuelve a aparecer"); });
        } else {
            labels.add("Ocultar (no es música)");
            actions.add(() -> {
                lib.hide(s.id);
                refreshRows();
                toast("Ocultada. Puedes recuperarla en Carpetas › Canciones ocultas");
            });
        }
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(s.title)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void chooseFolderFor(Song s) {
        final int n = lib.folders.size();
        String[] items = new String[n + 1];
        for (int i = 0; i < n; i++) items[i] = lib.folders.get(i).name;
        items[n] = "+ Nueva carpeta…";
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Agregar a carpeta")
                .setItems(items, (d, which) -> {
                    if (which == n) {
                        askName("Nueva carpeta", "", name -> {
                            Library.Folder f = lib.create(name);
                            lib.add(f, s.id);
                            refreshRows();
                            toast("Agregada a \"" + name + "\"");
                        });
                    } else {
                        Library.Folder f = lib.folders.get(which);
                        toast(lib.add(f, s.id) ? "Agregada a \"" + f.name + "\"" : "Ya estaba en \"" + f.name + "\"");
                        refreshRows();
                    }
                })
                .show();
    }

    private void folderOptions(Library.Folder f) {
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(f.name)
                .setItems(new String[]{"Agregar canciones", "Cambiar nombre", "Eliminar carpeta"}, (d, which) -> {
                    if (which == 0) {
                        pickSongsFor(f);
                    } else if (which == 1) {
                        askName("Cambiar nombre", f.name, name -> {
                            lib.rename(f, name);
                            paintTabs();
                            refreshRows();
                        });
                    } else {
                        new AlertDialog.Builder(this, DIALOG_THEME)
                                .setTitle("¿Eliminar \"" + f.name + "\"?")
                                .setMessage("Solo se borra la carpeta. Tus canciones siguen en el celular.")
                                .setPositiveButton("Eliminar", (d2, w2) -> {
                                    lib.delete(f);
                                    if (openFolder == f) setMode(MODE_FOLDERS); else refreshRows();
                                    toast("Carpeta eliminada");
                                })
                                .setNegativeButton("Cancelar", null)
                                .show();
                    }
                })
                .show();
    }

    /** Permite marcar varias canciones a la vez para meterlas en una carpeta. */
    private void pickSongsFor(Library.Folder f) {
        if (f == null) return;
        final List<Song> choices = new ArrayList<>();
        for (Song s : all) if (!lib.hidden.contains(s.id)) choices.add(s);
        if (choices.isEmpty()) { toast("No hay canciones para agregar"); return; }
        String[] items = new String[choices.size()];
        boolean[] checked = new boolean[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            Song s = choices.get(i);
            items[i] = s.title + "  —  " + s.artist;
            checked[i] = f.ids.contains(s.id);
        }
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Agregar a \"" + f.name + "\"")
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Listo", (d, w) -> {
                    int added = 0;
                    for (int i = 0; i < choices.size(); i++) {
                        long id = choices.get(i).id;
                        if (checked[i] && !f.ids.contains(id)) { f.ids.add(id); added++; }
                        if (!checked[i]) f.ids.remove(Long.valueOf(id));
                    }
                    lib.save();
                    refreshRows();
                    if (added > 0) toast(added + " canción(es) agregada(s)");
                })
                .setNegativeButton("Cancelar", null)
                .show();
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

    // ---------------- Buscar música en el celular ----------------

    private static boolean looksLikeMusic(String path, String mime, String title) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        for (String bad : NOT_MUSIC) {
            if (bad.startsWith("ptt-") || bad.startsWith("aud-")) {
                if (t.startsWith(bad)) return false;      // notas de voz de WhatsApp (PTT-..., AUD-...)
            } else if (p.contains(bad)) {
                return false;
            }
        }
        if (mime != null) {
            String m = mime.toLowerCase(Locale.ROOT);
            if (m.contains("amr") || m.contains("3gpp")) return false; // grabaciones de voz y llamadas
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private void loadSongs(boolean showToast) {
        new Thread(() -> {
            final List<Song> found = new ArrayList<>();
            String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.MIME_TYPE
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
                        String path = c.getString(6);
                        String mime = c.getString(7);
                        if (dur > 0 && dur < 30000) continue;          // menos de 30 s: no es una canción
                        if (!looksLikeMusic(path, mime, t)) continue;  // notas de voz, grabaciones, etc.
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
                byId.clear();
                for (Song s : found) byId.put(s.id, s);
                loaded = true;
                refreshRows();
                if (showToast) toast("Biblioteca actualizada");
            });
        }).start();
    }

    // ---------------- Reproducción ----------------

    private void playFrom(int index) {
        if (index < 0 || index >= rowSongs.size()) return;
        startService(new Intent(this, MusicService.class));
        List<Song> copy = new ArrayList<>(rowSongs);
        if (svc != null) {
            svc.playList(copy, index);
        } else {
            pendingList = copy;
            pendingIndex = index;
        }
    }

    @Override
    public void onPlayerChanged() { updatePlayer(); }

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

    private static class SongHolder {
        ImageView art;
        TextView title, sub;
        ImageButton more;
    }

    private static class RowHolder {
        ImageView icon;
        TextView title, sub;
    }

    private class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) { return rows.get(p) instanceof Song ? 0 : 1; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object o = rows.get(position);
            return o instanceof Song ? songView((Song) o, convertView) : otherView(o, convertView);
        }

        private View songView(Song s, View convertView) {
            SongHolder h;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(2), dp(8));
                h = new SongHolder();
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
                h.more = iconButton(R.drawable.ic_more, 40, MUT);
                h.more.setFocusable(false);
                row.addView(h.more);
                row.setTag(h);
                convertView = row;
            } else {
                h = (SongHolder) convertView.getTag();
            }
            Song cur = svc != null ? svc.getCurrentSong() : null;
            boolean on = cur != null && cur.id == s.id;
            h.title.setText(s.title);
            h.title.setTextColor(on ? ACC : TXT);
            h.sub.setText(s.artist + "  ·  " + Song.fmt(s.duration));
            h.more.setOnClickListener(v -> songOptions(s));
            convertView.setBackground(on ? rounded(CARD2, 12, 0) : null);
            ArtLoader.into(MainActivity.this, s, h.art);
            return convertView;
        }

        private View otherView(Object o, View convertView) {
            RowHolder h;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                h = new RowHolder();
                h.icon = new ImageView(MainActivity.this);
                h.icon.setBackground(rounded(CARD2, 8, 0));
                h.icon.setPadding(dp(12), dp(12), dp(12), dp(12));
                row.addView(h.icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
                LinearLayout txt = new LinearLayout(MainActivity.this);
                txt.setOrientation(LinearLayout.VERTICAL);
                txt.setPadding(dp(12), 0, 0, 0);
                h.title = text(15, TXT, true);
                h.sub = text(13, MUT, false);
                txt.addView(h.title);
                txt.addView(h.sub);
                row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.setTag(h);
                convertView = row;
            } else {
                h = (RowHolder) convertView.getTag();
            }
            if (o instanceof Library.Folder) {
                Library.Folder f = (Library.Folder) o;
                int n = 0;
                for (Long id : f.ids) if (byId.containsKey(id)) n++;
                h.icon.setImageResource(R.drawable.ic_folder);
                h.icon.setImageTintList(ColorStateList.valueOf(ACC));
                h.title.setText(f.name);
                h.title.setTextColor(TXT);
                h.sub.setText(n == 1 ? "1 canción" : n + " canciones");
                h.sub.setVisibility(View.VISIBLE);
            } else if (ROW_NEW_FOLDER.equals(o)) {
                h.icon.setImageResource(R.drawable.ic_add);
                h.icon.setImageTintList(ColorStateList.valueOf(ACC));
                h.title.setText("Nueva carpeta");
                h.title.setTextColor(ACC);
                h.sub.setVisibility(View.GONE);
            } else if (ROW_ADD_SONGS.equals(o)) {
                h.icon.setImageResource(R.drawable.ic_add);
                h.icon.setImageTintList(ColorStateList.valueOf(ACC));
                h.title.setText("Agregar canciones");
                h.title.setTextColor(ACC);
                h.sub.setVisibility(View.GONE);
            } else if (ROW_HIDDEN.equals(o)) {
                int n = hiddenCount();
                h.icon.setImageResource(R.drawable.ic_hidden);
                h.icon.setImageTintList(ColorStateList.valueOf(MUT));
                h.title.setText("Canciones ocultas");
                h.title.setTextColor(MUT);
                h.sub.setText(n == 1 ? "1 oculta" : n + " ocultas");
                h.sub.setVisibility(View.VISIBLE);
            }
            return convertView;
        }
    }
}
