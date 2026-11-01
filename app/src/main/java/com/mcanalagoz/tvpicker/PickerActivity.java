package com.mcanalagoz.tvpicker;

import android.app.Activity;
import android.app.AlertDialog;
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
 * File picker that stands in for the system SAF picker on Android TVs that lack one.
 *
 * Supported intents:
 *  - ACTION_OPEN_DOCUMENT / ACTION_GET_CONTENT : pick files (EXTRA_ALLOW_MULTIPLE, EXTRA_MIME_TYPES)
 *  - ACTION_OPEN_DOCUMENT_TREE                 : pick a folder (tree URI with persistable permission)
 *  - ACTION_CREATE_DOCUMENT                    : pick a folder and create a new file named EXTRA_TITLE
 *
 * Remote: OK = open folder / mark file, Back = parent folder (cancel at the root),
 *         Channel +/- and fast-forward/rewind keys jump 15 items.
 */
public class PickerActivity extends Activity {

    /** Virtual top level that lists every mounted volume (internal storage, SD cards, USB drives). */
    private static final String ROOT = StorageProvider.TOP;
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
    private Button newFolderButton;

    private Mode mode;
    private boolean allowMultiple;
    private String suggestedName;
    private String currentDocId = ROOT;
    /** True when more than one volume is mounted, so "Up" from a volume root returns to the volume list. */
    private boolean multiVolume;

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
        newFolderButton = findViewById(R.id.new_folder);

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

        // MIME filter: use EXTRA_MIME_TYPES if present, otherwise the intent type.
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
            suggestedName = getString(R.string.default_file_name);
        }

        setupActionButton();
        setupHint();

        adapter = new EntryAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(this::onEntryClicked);

        if (!SetupActivity.hasFileAccess(this)) {
            showNoAccessDialog();
        } else if (getCallingPackage() == null) {
            // Started without a result channel (the "Just once" chooser case): our answer would get lost.
            showNoResultDialog();
        }

        load(ROOT);
    }

    private void showNoAccessDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_no_access_title)
                .setMessage(R.string.dialog_no_access_msg)
                .setPositiveButton(R.string.dialog_open_setup,
                        (d, w) -> startActivity(new Intent(this, SetupActivity.class)))
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showNoResultDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_no_result_title)
                .setMessage(R.string.dialog_no_result_msg)
                .setPositiveButton(R.string.dialog_ok, null)
                .show();
    }

    private void setupActionButton() {
        switch (mode) {
            case OPEN_TREE:
                actionButton.setText(R.string.btn_select_folder);
                actionButton.setOnClickListener(v -> finishWithTree(currentDocId));
                actionButton.setVisibility(View.VISIBLE);
                break;
            case CREATE:
                actionButton.setText(getString(R.string.btn_save_here, suggestedName));
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
                hintView.setText(R.string.hint_pick_folder);
                break;
            case CREATE:
                hintView.setText(R.string.hint_save_folder);
                break;
            case OPEN_FILE:
            default:
                hintView.setText(allowMultiple ? R.string.hint_multi : R.string.hint_single);
                break;
        }
    }

    private void updateMultiButton() {
        actionButton.setText(getString(R.string.btn_confirm_selection, selected.size()));
    }

    private void load(String docId) {
        currentDocId = docId;
        pathView.setText(displayPath(docId));

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
                        // In folder and save modes files are shown for context only (not selectable).
                        if (e.dir || mode != Mode.OPEN_FILE || matchesAnyFilter(mime)) {
                            loaded.add(e);
                        }
                    }
                }
            } catch (RuntimeException ex) {
                runOnUiThread(() -> Toast.makeText(this, R.string.toast_read_failed, Toast.LENGTH_SHORT).show());
            }

            if (ROOT.equals(docId)) {
                if (loaded.size() == 1) {
                    // Only internal storage is mounted: skip the volume list.
                    String only = loaded.get(0).docId;
                    runOnUiThread(() -> {
                        multiVolume = false;
                        load(only);
                    });
                    return;
                }
                multiVolume = true;
            }
            runOnUiThread(() -> show(loaded));
        }).start();
    }

    private void show(List<Entry> loaded) {
        entries.clear();
        entries.addAll(loaded);
        upButton.setEnabled(parentOf(currentDocId) != null);
        // Nothing can be created or selected on the volume list itself.
        boolean volumeList = ROOT.equals(currentDocId);
        actionButton.setEnabled(!volumeList);
        newFolderButton.setEnabled(!volumeList);
        countView.setText(getResources().getQuantityString(R.plurals.item_count, entries.size(), entries.size()));
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

    /** "primary:Download/psp" -> "primary:Download"; a volume root goes up to the volume list. */
    private String parentOf(String docId) {
        if (ROOT.equals(docId)) {
            return null;
        }
        int colon = docId.indexOf(':');
        String prefix = docId.substring(0, colon + 1);
        String rel = docId.substring(colon + 1);
        if (rel.isEmpty()) {
            return multiVolume ? ROOT : null;
        }
        int idx = rel.lastIndexOf('/');
        return idx < 0 ? prefix : prefix + rel.substring(0, idx);
    }

    /** "primary:Download/psp" -> "/Download/psp"; other volumes get their key in front. */
    private static String displayPath(String docId) {
        if (ROOT.equals(docId)) {
            return "/";
        }
        int colon = docId.indexOf(':');
        String volume = docId.substring(0, colon);
        String rel = docId.substring(colon + 1);
        return ("primary".equals(volume) ? "" : "[" + volume + "] ") + "/" + rel;
    }

    private void goUp() {
        String parent = parentOf(currentDocId);
        if (parent != null) {
            load(parent);
        }
    }

    /** Remote-navigable rows: badge (FOLDER / FILE / SELECTED), name and a short hint. */
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

            int badgeColor = e.dir ? R.color.accent : R.color.badge_file;
            if (!e.dir && allowMultiple && selected.contains(e.docId)) {
                badgeColor = R.color.success;
            }
            badge.setBackgroundColor(getColor(badgeColor));

            if (e.dir) {
                badge.setText(R.string.badge_folder);
                meta.setText(R.string.meta_open);
            } else if (mode != Mode.OPEN_FILE) {
                badge.setText(R.string.badge_file);
                meta.setText(R.string.meta_not_selectable);
            } else if (allowMultiple && selected.contains(e.docId)) {
                badge.setText(R.string.badge_selected);
                meta.setText(R.string.meta_deselect);
            } else {
                badge.setText(R.string.badge_file);
                meta.setText(allowMultiple ? R.string.meta_add : R.string.meta_select);
            }
            return row;
        }
    }

    /**
     * Supports several MIME types: "*\/*" (all), "image/*" (type family),
     * and exact matches. A file is shown if any entry in EXTRA_MIME_TYPES matches.
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
            Toast.makeText(this, getString(R.string.toast_folders_only), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, getString(R.string.toast_select_at_least_one), Toast.LENGTH_SHORT).show();
            return;
        }
        finishWithFiles(new ArrayList<>(selected));
    }

    /**
     * File result. With several files all are returned through ClipData; the first
     * is also returned via getData() (for apps that expect a single file).
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
     * Returns a tree URI when a folder is chosen. Read + write + prefix + persistable grant:
     *  - PREFIX: every URI under the folder (including …/children) is readable.
     *  - PERSISTABLE + READ+WRITE: apps such as Lemuroid can
     *    call takePersistableUriPermission.
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

    /** Small name-entry dialog. The keyboard's done key triggers "Create". */
    private void askNewFolderName() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.dialog_folder_name);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_new_folder)
                .setView(input)
                .setPositiveButton(R.string.dialog_create, (d, w) -> createFolder(input.getText().toString().trim()))
                .setNegativeButton(R.string.dialog_cancel, null)
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

    /** Creates a new folder under the current one (SAF createDocument, DIR). */
    private void createFolder(String name) {
        if (name.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_name_empty), Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                Uri parent = DocumentsContract.buildDocumentUri(StorageProvider.AUTHORITY, currentDocId);
                Uri created = DocumentsContract.createDocument(
                        getContentResolver(), parent, Document.MIME_TYPE_DIR, name);
                runOnUiThread(() -> {
                    Toast.makeText(this,
                            created == null ? getString(R.string.toast_folder_failed) : getString(R.string.toast_folder_created, name),
                            Toast.LENGTH_SHORT).show();
                    load(currentDocId);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.toast_error, ex.getMessage()), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** ACTION_CREATE_DOCUMENT: creates a new file named EXTRA_TITLE in the current folder. */
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
                        Toast.makeText(this, getString(R.string.toast_file_failed), Toast.LENGTH_SHORT).show();
                    } else {
                        finishWithCreated(created);
                    }
                });
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.toast_save_failed, ex.getMessage()), Toast.LENGTH_LONG).show());
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

    /** Remote shortcuts: channel +/- and fast-forward/rewind keys jump 15 items. */
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
