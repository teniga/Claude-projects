package nl.screenshotopruimer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PICK_FOLDER = 1;
    private static final int REQ_NOTIFICATIONS = 2;

    private TextView folderText;
    private TextView statusText;
    private EditText daysInput;
    private Switch autoSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        folderText = (TextView) findViewById(R.id.folder_text);
        statusText = (TextView) findViewById(R.id.status_text);
        daysInput = (EditText) findViewById(R.id.days_input);
        autoSwitch = (Switch) findViewById(R.id.auto_switch);

        daysInput.setText(String.valueOf(Cleaner.days(this)));
        autoSwitch.setChecked(Cleaner.prefs(this).getBoolean(Cleaner.KEY_ENABLED, false));

        findViewById(R.id.pick_folder).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFolder(); }
        });
        findViewById(R.id.save_days).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveDays(); }
        });
        findViewById(R.id.preview).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { preview(); }
        });
        findViewById(R.id.clean_now).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmCleanNow(); }
        });
        autoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (checked && Cleaner.treeUri(MainActivity.this) == null) {
                    toast("Kies eerst een map");
                    b.setChecked(false);
                    return;
                }
                Cleaner.prefs(MainActivity.this).edit().putBoolean(Cleaner.KEY_ENABLED, checked).apply();
                Cleaner.applySchedule(MainActivity.this);
                refresh();
            }
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATIONS);
        }

        Cleaner.applySchedule(this);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        Uri current = Cleaner.treeUri(this);
        Uri initial = current != null
                ? DocumentsContract.buildDocumentUriUsingTree(current, DocumentsContract.getTreeDocumentId(current))
                : DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", "primary:Pictures/Screenshots");
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        try {
            startActivityForResult(i, REQ_PICK_FOLDER);
        } catch (Exception e) {
            toast("Mapkiezer kon niet worden geopend");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            toast("Geen blijvende toegang tot deze map gekregen");
            return;
        }
        // Release the permission on a previously chosen folder.
        Uri old = Cleaner.treeUri(this);
        if (old != null && !old.equals(tree)) {
            try {
                getContentResolver().releasePersistableUriPermission(old,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
        Cleaner.prefs(this).edit().putString(Cleaner.KEY_TREE, tree.toString()).apply();
        Cleaner.applySchedule(this);
        refresh();
    }

    private void saveDays() {
        int days;
        try {
            days = Integer.parseInt(daysInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            days = -1;
        }
        if (days < 1 || days > 3650) {
            toast("Vul een aantal dagen in tussen 1 en 3650");
            return;
        }
        Cleaner.prefs(this).edit().putInt(Cleaner.KEY_DAYS, days).apply();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(daysInput.getWindowToken(), 0);
        daysInput.clearFocus();
        toast("Opgeslagen: " + days + " dagen");
        refresh();
    }

    private void preview() {
        final Button btn = (Button) findViewById(R.id.preview);
        btn.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                String title;
                try {
                    List<Cleaner.OldFile> files = Cleaner.findOldFiles(MainActivity.this);
                    title = files.size() == 1 ? "1 bestand ouder dan " + Cleaner.days(MainActivity.this) + " dagen"
                            : files.size() + " bestanden ouder dan " + Cleaner.days(MainActivity.this) + " dagen";
                    StringBuilder sb = new StringBuilder();
                    DateFormat df = DateFormat.getDateInstance(DateFormat.SHORT);
                    int shown = 0;
                    for (Cleaner.OldFile f : files) {
                        if (shown++ == 200) {
                            sb.append("… en nog ").append(files.size() - 200).append(" meer");
                            break;
                        }
                        sb.append(df.format(new Date(f.modified))).append("  ").append(f.name).append('\n');
                    }
                    msg = files.isEmpty() ? "Er is niets om op te ruimen." : sb.toString();
                } catch (Exception e) {
                    title = "Voorbeeld";
                    msg = e.getMessage();
                }
                final String t = title, m = msg;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        btn.setEnabled(true);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(t).setMessage(m)
                                .setPositiveButton("OK", null).show();
                    }
                });
            }
        }).start();
    }

    private void confirmCleanNow() {
        if (Cleaner.treeUri(this) == null) {
            toast("Kies eerst een map");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Nu opruimen?")
                .setMessage("Alle bestanden ouder dan " + Cleaner.days(this)
                        + " dagen in de gekozen map worden definitief verwijderd.")
                .setNegativeButton("Annuleren", null)
                .setPositiveButton("Verwijderen", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { cleanNow(); }
                })
                .show();
    }

    private void cleanNow() {
        final Button btn = (Button) findViewById(R.id.clean_now);
        btn.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                final Cleaner.Result r = Cleaner.run(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        btn.setEnabled(true);
                        toast(Cleaner.describe(r));
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void refresh() {
        Uri tree = Cleaner.treeUri(this);
        folderText.setText(tree == null ? "Nog geen map gekozen" : folderLabel(tree));

        SharedPreferences p = Cleaner.prefs(this);
        StringBuilder sb = new StringBuilder();
        boolean on = p.getBoolean(Cleaner.KEY_ENABLED, false) && tree != null;
        sb.append(on ? "Automatisch opruimen staat AAN (1× per dag)." : "Automatisch opruimen staat UIT.");
        long last = p.getLong(Cleaner.KEY_LAST_RUN, 0);
        if (last > 0) {
            sb.append("\nLaatst opgeruimd: ")
                    .append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(last)));
            String err = p.getString(Cleaner.KEY_LAST_ERROR, null);
            if (err != null) sb.append("\nFout: ").append(err);
            else sb.append(" — ").append(p.getInt(Cleaner.KEY_LAST_COUNT, 0)).append(" verwijderd");
        }
        statusText.setText(sb.toString());
    }

    /** Turns "primary:Pictures/Screenshots" into "Interne opslag/Pictures/Screenshots". */
    private static String folderLabel(Uri tree) {
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception e) {
            return tree.toString();
        }
        int colon = id.indexOf(':');
        if (colon < 0) return id;
        String volume = id.substring(0, colon);
        String path = id.substring(colon + 1);
        String vol = "primary".equals(volume) ? "Interne opslag" : "SD-kaart (" + volume + ")";
        return path.isEmpty() ? vol : vol + "/" + path;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
