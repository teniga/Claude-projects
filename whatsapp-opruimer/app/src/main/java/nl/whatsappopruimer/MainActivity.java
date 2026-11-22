package nl.whatsappopruimer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements ScanManager.Listener {

    private static final int REQ_TREE = 1;
    private static final int REQ_PERMS = 2;
    private static final String WHATSAPP_IMAGES =
            "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images";

    private TextView folder, status, permission;
    private Button scan, stop, review;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        folder = findViewById(R.id.folder);
        status = findViewById(R.id.status);
        permission = findViewById(R.id.permission);
        scan = findViewById(R.id.scan);
        stop = findViewById(R.id.stop);
        review = findViewById(R.id.review);
        progress = findViewById(R.id.progress);

        findViewById(R.id.pickFolder).setOnClickListener(v -> pickFolder());

        CheckBox sub = findViewById(R.id.subfolders);
        sub.setChecked(Prefs.subfolders(this));
        sub.setOnCheckedChangeListener((b, c) -> Prefs.setSubfolders(this, c));

        CheckBox shots = findViewById(R.id.shotsAlways);
        shots.setChecked(Prefs.shotsAlways(this));
        shots.setOnCheckedChangeListener((b, c) -> Prefs.setShotsAlways(this, c));

        EditText days = findViewById(R.id.skipDays);
        days.setText(String.valueOf(Prefs.skipDays(this)));
        days.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                try {
                    Prefs.setSkipDays(MainActivity.this, Math.max(0, Integer.parseInt(s.toString().trim())));
                } catch (NumberFormatException ignored) {
                    Prefs.setSkipDays(MainActivity.this, 0);
                }
            }
        });

        RadioGroup level = findViewById(R.id.level);
        int[] ids = {R.id.level0, R.id.level1, R.id.level2};
        level.check(ids[Math.max(0, Math.min(2, Prefs.level(this)))]);
        level.setOnCheckedChangeListener((g, id) -> {
            for (int i = 0; i < ids.length; i++) if (ids[i] == id) Prefs.setLevel(this, i);
        });

        scan.setOnClickListener(v -> startScan());
        stop.setOnClickListener(v -> ScanManager.cancelled = true);
        review.setOnClickListener(v -> {
            if (Prefs.tree(this) == null) {
                Toast.makeText(this, "Kies eerst een map", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, ReviewActivity.class));
        });

        requestPermissionsIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ScanManager.addListener(this);
        showFolder();
        checkMediaPermission();
        onScanUpdate();
    }

    @Override
    protected void onPause() {
        ScanManager.removeListener(this);
        super.onPause();
    }

    @Override
    public void onScanUpdate() {
        boolean running = ScanManager.running;
        scan.setEnabled(!running);
        stop.setEnabled(running);
        review.setEnabled(!running);
        progress.setVisibility(running ? View.VISIBLE : View.GONE);
        progress.setProgress(ScanManager.progressPercent());
        status.setText(ScanManager.status);
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        Uri current = Prefs.tree(this);
        Uri initial = current != null
                ? DocumentsContract.buildDocumentUriUsingTree(current, DocumentsContract.getTreeDocumentId(current))
                : DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", WHATSAPP_IMAGES);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException e) {
                Toast.makeText(this, "Geen toegang tot deze map gekregen", Toast.LENGTH_LONG).show();
                return;
            }
            Prefs.setTree(this, tree);
            ReviewActivity.invalidate();
            showFolder();
        }
    }

    private void showFolder() {
        Uri tree = Prefs.tree(this);
        if (tree == null) {
            folder.setText("Nog geen map gekozen");
            return;
        }
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        folder.setText(path.isEmpty() ? "(hoofdmap)" : path);
    }

    private void startScan() {
        Uri tree = Prefs.tree(this);
        if (tree == null) {
            Toast.makeText(this, "Kies eerst een map", Toast.LENGTH_SHORT).show();
            return;
        }
        ReviewActivity.invalidate();
        ScanManager.status = "Starten…";
        ScanService.start(this, tree, Prefs.subfolders(this), Prefs.skipDays(this));
        onScanUpdate();
    }

    private String mediaPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void requestPermissionsIfNeeded() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(mediaPermission()) != PackageManager.PERMISSION_GRANTED) need.add(mediaPermission());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        checkMediaPermission();
    }

    private void checkMediaPermission() {
        boolean granted = checkSelfPermission(mediaPermission()) == PackageManager.PERMISSION_GRANTED;
        if (granted || !Trasher.systemTrashAvailable()) {
            permission.setVisibility(View.GONE);
            return;
        }
        permission.setVisibility(View.VISIBLE);
        permission.setText("Geef de app toegang tot \"alle foto's\" (tik hier). Zonder die toegang kan de app de "
                + "prullenbak van Android niet gebruiken en verplaatst hij afbeeldingen naar de submap "
                + FileLister.TRASH_DIR + ".");
        permission.setOnClickListener(v -> {
            if (shouldShowRequestPermissionRationale(mediaPermission())) {
                requestPermissions(new String[]{mediaPermission()}, REQ_PERMS);
            } else {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            }
        });
    }
}
