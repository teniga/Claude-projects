package nl.whatsappopruimer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Raster met de scanresultaten per groep. Pas na bevestiging gaat er iets naar de prullenbak. */
public class ReviewActivity extends Activity {

    private static final int REQ_TRASH = 10;

    static final int TAB_NO_PERSON = 0, TAB_DOUBT = 1, TAB_SCREENSHOT = 2, TAB_PERSON = 3;
    private static final String[] TAB_NAMES = {"Geen persoon", "Twijfel", "Screenshots", "Met persoon"};

    static final class Entry {
        ResultDb.Row row;
        int tab;
        boolean selected;
    }

    // Bewaard over schermrotatie en terug-navigatie heen, zodat je aanvinkwerk niet verloren gaat.
    private static List<Entry> state;
    private static String stateKey;
    private static int notScanned;

    static void invalidate() {
        state = null;
        stateKey = null;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Entry> visible = new ArrayList<>();
    private final List<Button> tabButtons = new ArrayList<>();
    private int currentTab = TAB_NO_PERSON;
    private Uri tree;
    private Thumbs thumbs;
    private Adapter adapter;
    private TextView hint, empty;
    private Button trash;
    private boolean busy;

    // Voortgang van het verwijderen
    private Trasher.Plan plan;
    private int batchIndex;
    private int trashedCount, failedCount, cancelledCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review);
        tree = Prefs.tree(this);
        if (tree == null) { finish(); return; }

        hint = findViewById(R.id.hint);
        empty = findViewById(R.id.empty);
        trash = findViewById(R.id.trash);

        int span = Math.max(3, getResources().getDisplayMetrics().widthPixels
                / (int) (110 * getResources().getDisplayMetrics().density));
        int thumbSize = getResources().getDisplayMetrics().widthPixels / span;
        thumbs = new Thumbs(getContentResolver(), Math.max(128, thumbSize));

        RecyclerView grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, span));
        adapter = new Adapter();
        grid.setAdapter(adapter);

        LinearLayout tabs = findViewById(R.id.tabs);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            Button b = new Button(this, null, 0, android.R.style.Widget_Material_Button_Borderless);
            b.setAllCaps(false);
            final int t = i;
            b.setOnClickListener(v -> showTab(t));
            tabs.addView(b);
            tabButtons.add(b);
        }

        findViewById(R.id.all).setOnClickListener(v -> setAll(true));
        findViewById(R.id.none).setOnClickListener(v -> setAll(false));
        trash.setOnClickListener(v -> confirmTrash());

        loadState();
    }

    @Override
    protected void onDestroy() {
        if (thumbs != null) thumbs.shutdown();
        super.onDestroy();
    }

    private void loadState() {
        int level = Prefs.level(this);
        boolean shotsAlways = Prefs.shotsAlways(this);
        String key = tree + "|" + level + "|" + shotsAlways;
        if (state != null && key.equals(stateKey)) {
            showTab(currentTab);
            return;
        }
        busy = true;
        hint.setText("Laden…");
        new Thread(() -> {
            List<ResultDb.Row> rows = ResultDb.get(this).seenRows(tree.toString());
            List<Entry> list = new ArrayList<>();
            int missing = 0;
            for (ResultDb.Row r : rows) {
                if (!r.analyzed) { missing++; continue; }
                Entry e = new Entry();
                e.row = r;
                if (r.error) {
                    e.tab = TAB_PERSON;
                    e.selected = false;
                } else {
                    Rules.Verdict v = Rules.verdict(r.faces, r.person, level);
                    boolean shot = Rules.isScreenshot(r.name, r.width, r.height);
                    e.tab = shot ? TAB_SCREENSHOT
                            : v == Rules.Verdict.NO_PERSON ? TAB_NO_PERSON
                            : v == Rules.Verdict.DOUBT ? TAB_DOUBT : TAB_PERSON;
                    e.selected = Rules.selectedByDefault(v, shot, shotsAlways);
                }
                list.add(e);
            }
            int fMissing = missing;
            main.post(() -> {
                state = list;
                stateKey = key;
                notScanned = fMissing;
                busy = false;
                showTab(currentTab);
            });
        }).start();
    }

    private void showTab(int tab) {
        currentTab = tab;
        visible.clear();
        int[] counts = new int[TAB_NAMES.length];
        for (Entry e : state) {
            counts[e.tab]++;
            if (e.tab == tab) visible.add(e);
        }
        for (int i = 0; i < tabButtons.size(); i++) {
            Button b = tabButtons.get(i);
            b.setText(TAB_NAMES[i] + " (" + counts[i] + ")");
            b.setTypeface(null, i == tab ? Typeface.BOLD : Typeface.NORMAL);
            b.setTextColor(getColor(i == tab ? R.color.accent : android.R.color.darker_gray));
        }
        String h;
        switch (tab) {
            case TAB_NO_PERSON: h = "Hierop is geen persoon gevonden. Aangevinkte afbeeldingen (rood) gaan naar de prullenbak."; break;
            case TAB_DOUBT: h = "Hier twijfelt de herkenning. Ze blijven staan, tenzij je ze aanvinkt."; break;
            case TAB_SCREENSHOT: h = Prefs.shotsAlways(this)
                    ? "Screenshots (herkend aan schermformaat). Ze gaan allemaal weg, ook met persoon."
                    : "Screenshots (herkend aan schermformaat). Alleen die zonder persoon zijn aangevinkt."; break;
            default: h = "Hierop is een persoon gevonden (of de afbeelding was onleesbaar). Ze blijven staan.";
        }
        h += "\nTik = aan/uit. Vasthouden = groot bekijken.";
        if (notScanned > 0) h += "\n" + notScanned + " afbeeldingen zijn nog niet gescand en worden niet getoond.";
        hint.setText(h);
        empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        updateTrashButton();
    }

    private void setAll(boolean sel) {
        if (busy) return;
        for (Entry e : visible) e.selected = sel;
        adapter.notifyDataSetChanged();
        updateTrashButton();
    }

    private int selectedCount() {
        int n = 0;
        if (state != null) for (Entry e : state) if (e.selected) n++;
        return n;
    }

    private void updateTrashButton() {
        int n = selectedCount();
        trash.setText(busy ? "Bezig…" : "Naar prullenbak (" + n + ")");
        trash.setEnabled(!busy && n > 0);
    }

    // ---- Verwijderen ----

    private void confirmTrash() {
        int n = selectedCount();
        if (n == 0 || busy) return;
        String how = Trasher.systemTrashAvailable()
                ? "Android vraagt daarna zelf nog om toestemming (bij veel foto's in groepen van " + Trasher.BATCH
                  + "). In de prullenbak van je Galerij of Google Foto's kun je ze 30 dagen terugzetten."
                : "Ze worden verplaatst naar de submap " + FileLister.TRASH_DIR + "; die kun je later zelf legen.";
        new AlertDialog.Builder(this)
                .setTitle(n + " afbeeldingen naar de prullenbak?")
                .setMessage(how)
                .setPositiveButton("Naar prullenbak", (d, w) -> startTrash())
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void startTrash() {
        busy = true;
        updateTrashButton();
        trashedCount = failedCount = cancelledCount = 0;
        List<ResultDb.Row> rows = new ArrayList<>();
        for (Entry e : state) if (e.selected) rows.add(e.row);
        new Thread(() -> {
            Trasher.Plan p = Trasher.plan(this, rows);
            main.post(() -> {
                plan = p;
                batchIndex = 0;
                nextBatch();
            });
        }).start();
    }

    private void nextBatch() {
        if (android.os.Build.VERSION.SDK_INT < 30 || batchIndex >= plan.mediaBatches.size()) {
            moveRest();
            return;
        }
        try {
            IntentSender sender = MediaStore.createTrashRequest(getContentResolver(),
                    plan.mediaBatches.get(batchIndex), true).getIntentSender();
            startIntentSenderForResult(sender, REQ_TRASH, null, 0, 0, 0);
        } catch (Exception e) {
            failedCount += plan.mediaBatchRows.get(batchIndex).size();
            batchIndex++;
            nextBatch();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TRASH || plan == null) return;
        List<ResultDb.Row> rows = plan.mediaBatchRows.get(batchIndex);
        if (resultCode == RESULT_OK) {
            removeDone(rows);
            trashedCount += rows.size();
            batchIndex++;
            nextBatch();
        } else {
            // Gebruiker weigerde: de rest niet meer vragen.
            for (int i = batchIndex; i < plan.mediaBatchRows.size(); i++) {
                cancelledCount += plan.mediaBatchRows.get(i).size();
            }
            batchIndex = plan.mediaBatches.size();
            moveRest();
        }
    }

    private void moveRest() {
        List<ResultDb.Row> rows = plan.moveRows;
        if (cancelledCount > 0) {
            // Geannuleerd: ook de rest niet meer verplaatsen.
            cancelledCount += rows.size();
            finishTrash(0);
            return;
        }
        if (rows.isEmpty()) {
            finishTrash(0);
            return;
        }
        new Thread(() -> {
            List<ResultDb.Row> ok = Trasher.moveToTrashFolder(this, tree, rows);
            main.post(() -> {
                removeDone(ok);
                failedCount += rows.size() - ok.size();
                finishTrash(ok.size());
            });
        }).start();
    }

    private void removeDone(List<ResultDb.Row> rows) {
        Set<String> done = new HashSet<>();
        for (ResultDb.Row r : rows) done.add(r.uri);
        ResultDb db = ResultDb.get(this);
        new Thread(() -> { for (String u : done) db.delete(u); }).start();
        List<Entry> keep = new ArrayList<>();
        for (Entry e : state) if (!done.contains(e.row.uri)) keep.add(e);
        state = keep;
    }

    private void finishTrash(int moved) {
        busy = false;
        plan = null;
        showTab(currentTab);
        StringBuilder sb = new StringBuilder();
        if (trashedCount > 0) sb.append(trashedCount).append(" naar de prullenbak van Android.\n");
        if (moved > 0) sb.append(moved).append(" verplaatst naar de submap ").append(FileLister.TRASH_DIR).append(".\n");
        if (cancelledCount > 0) sb.append(cancelledCount).append(" overgeslagen omdat je dit annuleerde.\n");
        if (failedCount > 0) sb.append(failedCount).append(" konden niet verplaatst worden en staan er nog.\n");
        if (sb.length() == 0) sb.append("Er is niets verplaatst.");
        new AlertDialog.Builder(this)
                .setTitle("Klaar")
                .setMessage(sb.toString().trim())
                .setPositiveButton("OK", null)
                .show();
    }

    // ---- Raster ----

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final View frame;
        final View mark;

        Holder(View v) {
            super(v);
            image = v.findViewById(R.id.image);
            frame = v.findViewById(R.id.frame);
            mark = v.findViewById(R.id.mark);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_thumb, parent, false));
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            Entry e = visible.get(position);
            thumbs.load(e.row.uri, h.image);
            bindSelection(h, e);
            h.itemView.setOnClickListener(v -> {
                if (busy) return;
                e.selected = !e.selected;
                bindSelection(h, e);
                updateTrashButton();
            });
            h.itemView.setOnLongClickListener(v -> {
                openImage(e.row.uri);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return visible.size();
        }
    }

    private void bindSelection(Holder h, Entry e) {
        h.frame.setVisibility(e.selected ? View.VISIBLE : View.GONE);
        h.mark.setVisibility(e.selected ? View.VISIBLE : View.GONE);
        h.image.setAlpha(e.selected ? 0.75f : 1f);
    }

    private void openImage(String uri) {
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, "Geen app gevonden om de afbeelding te openen", Toast.LENGTH_SHORT).show();
        }
    }
}
