package com.canalagoz.tvpicker;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sistem picker'ı olmayan Android TV'ler için SAF yerine geçen dosya seçici.
 *
 * Desteklenen intent'ler:
 *  - ACTION_OPEN_DOCUMENT / ACTION_GET_CONTENT : dosya seçimi (EXTRA_ALLOW_MULTIPLE, EXTRA_MIME_TYPES)
 *  - ACTION_OPEN_DOCUMENT_TREE                 : klasör seçimi (kalıcı izinli tree URI)
 *  - ACTION_CREATE_DOCUMENT                    : klasör seçip EXTRA_TITLE adıyla yeni dosya oluşturma
 *
 * Kumanda: OK = klasöre gir / dosyayı işaretle, Geri = üst klasör (kökte iptal),
 *          Kanal +/- ve ileri/geri tuşları listede 15 öğe atlar.
 */
public class PickerActivity extends Activity {

    private static final String ROOT = "primary:";
    private static final int JUMP = 15;

    private enum Mode { OPEN_FILE, OPEN_TREE, CREATE }

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final List<String> mimeFilters = new ArrayList<>();

    private EntryAdapter adapter;
    private TextView pathView;
    private TextView countView;
    private TextView emptyView;
    private TextView hintView;
    private ListView listView;
    private Button upButton;
    private Button actionButton;

    private Mode mode;
    private boolean allowMultiple;
    private String suggestedName;
    private String currentDocId = ROOT;

    private static class Entry {
        final String docId;
        final String name;
        final String mime;
        final boolean dir;

        Entry(String docId, String name, String mime) {
            this.docId = docId;
            this.name = name;
            this.mime = mime;
            this.dir = Document.MIME_TYPE_DIR.equals(mime);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);

        pathView = findViewById(R.id.path);
        countView = findViewById(R.id.count);
        emptyView = findViewById(R.id.empty);
        hintView = findViewById(R.id.hint);
        listView = findViewById(R.id.list);
        upButton = findViewById(R.id.up_folder);
        actionButton = findViewById(R.id.select_folder);
        Button newFolderButton = findViewById(R.id.new_folder);

        upButton.setOnClickListener(v -> goUp());
        newFolderButton.setOnClickListener(v -> askNewFolderName());

        Intent intent = getIntent();
        String action = intent.getAction();
        if (Intent.ACTION_OPEN_DOCUMENT_TREE.equals(action)) {
            mode = Mode.OPEN_TREE;
        } else if (Intent.ACTION_CREATE_DOCUMENT.equals(action)) {
            mode = Mode.CREATE;
        } else {
            mode = Mode.OPEN_FILE;
        }

        allowMultiple = mode == Mode.OPEN_FILE
                && intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);

        // MIME filtresi: EXTRA_MIME_TYPES varsa onu, yoksa intent türünü kullan.
        String[] extraMimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        if (extraMimes != null && extraMimes.length > 0) {
            mimeFilters.addAll(Arrays.asList(extraMimes));
        } else if (intent.getType() != null && !intent.getType().isEmpty()) {
            mimeFilters.add(intent.getType());
        } else {
            mimeFilters.add("*/*");
        }

        suggestedName = intent.getStringExtra(Intent.EXTRA_TITLE);
        if (suggestedName == null || suggestedName.trim().isEmpty()) {
            suggestedName = "yeni_dosya";
        }

        setupActionButton();
        setupHint();

        adapter = new EntryAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(this::onEntryClicked);

        load(ROOT);
    }

    private void setupActionButton() {
        switch (mode) {
            case OPEN_TREE:
                actionButton.setText("Bu klasörü seç");
                actionButton.setOnClickListener(v -> finishWithTree(currentDocId));
                actionButton.setVisibility(View.VISIBLE);
                break;
            case CREATE:
                actionButton.setText("Buraya kaydet: " + suggestedName);
                actionButton.setOnClickListener(v -> createHere());
                actionButton.setVisibility(View.VISIBLE);
                break;
            case OPEN_FILE:
            default:
                if (allowMultiple) {
                    updateMultiButton();
                    actionButton.setOnClickListener(v -> finishWithFiles());
                    actionButton.setVisibility(View.VISIBLE);
                } else {
                    actionButton.setVisibility(View.GONE);
                }
                break;
        }
    }

    private void setupHint() {
        switch (mode) {
            case OPEN_TREE:
                hintView.setText("Klasöre girip sağdaki düğmeyle seç.\nGeri: üst klasör");
                break;
            case CREATE:
                hintView.setText("Klasöre girip sağdaki düğmeyle kaydet.\nGeri: üst klasör");
                break;
            case OPEN_FILE:
            default:
                hintView.setText(allowMultiple
                        ? "OK: dosyayı işaretle / kaldır.\nİşaretledikten sonra onay düğmesine bas."
                        : "OK: dosyayı seç.\nGeri: üst klasör");
                break;
        }
    }

    private void updateMultiButton() {
        actionButton.setText("Seçilenleri onayla (" + selected.size() + ")");
    }

    private void load(String docId) {
        currentDocId = docId;
        pathView.setText("/" + docId.replaceFirst("^primary:", ""));

        new Thread(() -> {
            List<Entry> loaded = new ArrayList<>();
            Uri childrenUri = DocumentsContract.buildChildDocumentsUri(StorageProvider.AUTHORITY, docId);

            try (Cursor c = getContentResolver().query(childrenUri, null, null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String id = c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID));
                        String name = c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME));
                        String mime = c.getString(c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE));
                        Entry e = new Entry(id, name, mime);
                        // Klasör ve kaydet modlarında dosyalar bağlam için görünür (seçilemez).
                        if (e.dir || mode != Mode.OPEN_FILE || matchesAnyFilter(mime)) {
                            loaded.add(e);
                        }
                    }
                }
            } catch (RuntimeException ex) {
                runOnUiThread(() -> Toast.makeText(this, "Klasör okunamadı", Toast.LENGTH_SHORT).show());
            }

            runOnUiThread(() -> show(loaded));
        }).start();
    }

    private void show(List<Entry> loaded) {
        entries.clear();
        entries.addAll(loaded);
        upButton.setEnabled(parentOf(currentDocId) != null);
        countView.setText(entries.size() + " öğe");
        refreshLabels();
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        listView.requestFocus();
        if (!entries.isEmpty()) {
            listView.setSelection(0);
        }
    }

    private void refreshLabels() {
        adapter.notifyDataSetChanged();
        if (allowMultiple) {
            updateMultiButton();
        }
    }

    /** "primary:Download/psp" -> "primary:Download"; kökte null. */
    private static String parentOf(String docId) {
        String rel = docId.replaceFirst("^primary:", "");
        if (rel.isEmpty()) {
            return null;
        }
        int idx = rel.lastIndexOf('/');
        return idx < 0 ? ROOT : ROOT + rel.substring(0, idx);
    }

    private void goUp() {
        String parent = parentOf(currentDocId);
        if (parent != null) {
            load(parent);
        }
    }

    /** Kumandayla gezilebilir satırlar: rozet (KLASÖR / DOSYA / SEÇİLDİ), ad ve kısa açıklama. */
    private class EntryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null
                    ? convertView
                    : getLayoutInflater().inflate(R.layout.item_entry, parent, false);

            Entry e = entries.get(position);
            TextView badge = row.findViewById(R.id.badge);
            TextView name = row.findViewById(R.id.name);
            TextView meta = row.findViewById(R.id.meta);

            name.setText(e.name);

            if (e.dir) {
                badge.setText("KLASÖR");
                meta.setText("Açmak için OK");
            } else if (mode != Mode.OPEN_FILE) {
                badge.setText("DOSYA");
                meta.setText("Bu modda seçilemez");
            } else if (allowMultiple && selected.contains(e.docId)) {
                badge.setText("SEÇİLDİ");
                meta.setText("Kaldırmak için tekrar OK");
            } else {
                badge.setText("DOSYA");
                meta.setText(allowMultiple ? "Eklemek için OK" : "Seçmek için OK");
            }
            return row;
        }
    }

    /**
     * Birden fazla MIME türünü destekler: "*\/*" (hepsi), "image/*" (tür ailesi),
     * tam eşleşmeler. EXTRA_MIME_TYPES içinde herhangi biri eşleşirse dosya gösterilir.
     */
    private boolean matchesAnyFilter(String mime) {
        if (mime == null) {
            return true;
        }
        for (String filter : mimeFilters) {
            if (filter.equals("*/*")) {
                return true;
            }
            if (filter.endsWith("/*")) {
                if (mime.startsWith(filter.substring(0, filter.length() - 1))) {
                    return true;
                }
            } else if (filter.equals(mime)) {
                return true;
            }
        }
        return false;
    }

    private void onEntryClicked(AdapterView<?> parent, View view, int position, long id) {
        Entry entry = entries.get(position);
        if (entry.dir) {
            load(entry.docId);
            return;
        }

        if (mode != Mode.OPEN_FILE) {
            Toast.makeText(this, "Bu modda sadece klasör seçilir", Toast.LENGTH_SHORT).show();
            return;
        }

        if (allowMultiple) {
            if (!selected.remove(entry.docId)) {
                selected.add(entry.docId);
            }
            refreshLabels();
            return;
        }

        finishWithFiles(Arrays.asList(entry.docId));
    }

    private void finishWithFiles() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "En az bir dosya işaretle", Toast.LENGTH_SHORT).show();
            return;
        }
        finishWithFiles(new ArrayList<>(selected));
    }

    /**
     * Dosya sonucu. Birden fazla dosya varsa ClipData ile hepsi döner; ilk dosya
     * getData() ile de verilir (tek dosya bekleyen uygulamalar için).
     */
    private void finishWithFiles(List<String> docIds) {
        Uri first = DocumentsContract.buildDocumentUri(StorageProvider.AUTHORITY, docIds.get(0));

        Intent result = new Intent();
        result.setData(first);
        if (docIds.size() > 1) {
            ClipData clip = ClipData.newUri(getContentResolver(), "selection", first);
            for (int i = 1; i < docIds.size(); i++) {
                clip.addItem(new ClipData.Item(
                        DocumentsContract.buildDocumentUri(StorageProvider.AUTHORITY, docIds.get(i))));
            }
            result.setClipData(clip);
        }
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        setResult(RESULT_OK, result);
        finish();
    }

    /**
     * Klasör seçildiğinde tree URI döner. Okuma + yazma + prefix + kalıcı izin:
     *  - PREFIX: klasör altındaki tüm URI'ler (…/children dahil) okunabilsin.
     *  - PERSISTABLE + READ+WRITE: Lemuroid gibi uygulamalar takePersistableUriPermission
     *    çağırabilsin.
     */
    private void finishWithTree(String docId) {
        Uri treeUri = DocumentsContract.buildTreeDocumentUri(StorageProvider.AUTHORITY, docId);

        Intent result = new Intent();
        result.setData(treeUri);
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        setResult(RESULT_OK, result);
        finish();
    }

    /** Ad girmek için küçük bir pencere. Klavyedeki onay tuşu "Oluştur"u tetikler. */
    private void askNewFolderName() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Klasör adı");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Yeni klasör")
                .setView(input)
                .setPositiveButton("Oluştur", (d, w) -> createFolder(input.getText().toString().trim()))
                .setNegativeButton("İptal", null)
                .create();

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_NEXT) {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
                return true;
            }
            return false;
        });

        dialog.show();
        input.requestFocus();
    }

    /** Mevcut klasörün altında yeni klasör oluşturur (SAF createDocument, DIR). */
    private void createFolder(String name) {
        if (name.isEmpty()) {
            Toast.makeText(this, "Ad boş olamaz", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                Uri parent = DocumentsContract.buildDocumentUri(StorageProvider.AUTHORITY, currentDocId);
                Uri created = DocumentsContract.createDocument(
                        getContentResolver(), parent, Document.MIME_TYPE_DIR, name);
                runOnUiThread(() -> {
                    Toast.makeText(this,
                            created == null ? "Klasör oluşturulamadı" : "Klasör oluşturuldu: " + name,
                            Toast.LENGTH_SHORT).show();
                    load(currentDocId);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Hata: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** ACTION_CREATE_DOCUMENT: mevcut klasörde EXTRA_TITLE adıyla yeni dosya oluşturur. */
    private void createHere() {
        String mime = getIntent().getType();
        if (mime == null || mime.isEmpty()) {
            mime = "application/octet-stream";
        }
        String finalMime = mime;

        new Thread(() -> {
            try {
                Uri parent = DocumentsContract.buildDocumentUri(StorageProvider.AUTHORITY, currentDocId);
                Uri created = DocumentsContract.createDocument(
                        getContentResolver(), parent, finalMime, suggestedName);
                runOnUiThread(() -> {
                    if (created == null) {
                        Toast.makeText(this, "Dosya oluşturulamadı", Toast.LENGTH_SHORT).show();
                    } else {
                        finishWithCreated(created);
                    }
                });
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Kaydedilemedi: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void finishWithCreated(Uri created) {
        Intent result = new Intent();
        result.setData(created);
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        setResult(RESULT_OK, result);
        finish();
    }

    /** Kumanda kısayolları: kanal +/- ve ileri/geri tuşları listede 15 öğe atlatır. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int jump = 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                jump = -JUMP;
                break;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                jump = JUMP;
                break;
            default:
                break;
        }
        if (jump != 0 && !entries.isEmpty()) {
            int current = listView.getSelectedItemPosition();
            if (current < 0) {
                current = 0;
            }
            int target = Math.max(0, Math.min(entries.size() - 1, current + jump));
            listView.setSelection(target);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        String parent = parentOf(currentDocId);
        if (parent != null) {
            load(parent);
            return;
        }
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }
}
