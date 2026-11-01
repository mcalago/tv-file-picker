package com.mcanalagoz.tvpicker;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Our own SAF provider for devices that ship without the system DocumentsUI.
 *
 * Document IDs are a volume key plus ":" plus a path relative to that volume's root
 * (e.g. "primary:Download/psp" or "1234-ABCD:Games"), the same format ExternalStorageProvider uses. Apps such as
 * PPSSPP join IDs like paths, so absolute paths used to break them.
 */
public class StorageProvider extends DocumentsProvider {

    public static final String AUTHORITY = "com.mcanalagoz.tvpicker.docs";
    /** Key of the internal storage volume. */
    private static final String PRIMARY = "primary";
    /** Virtual top-level document that lists every mounted volume (used by our own picker). */
    static final String TOP = "storage:";
    private static final String TAG = "TVPicker";

    private static final String[] ROOT_COLUMNS = {
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID
    };

    private static final String[] DOC_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    /** A mounted storage volume: internal storage, an SD card or a USB drive. */
    private static final class Volume {
        final String label;
        final File dir;

        Volume(String label, File dir) {
            this.label = label;
            this.dir = dir;
        }
    }

    /** Mounted volumes by key: "primary" first, then removable ones by UUID. */
    private Map<String, Volume> volumes() {
        Map<String, Volume> map = new LinkedHashMap<>();
        Context ctx = getContext();
        map.put(PRIMARY, new Volume(ctx.getString(R.string.picker_root_title),
                Environment.getExternalStorageDirectory()));

        StorageManager sm = ctx.getSystemService(StorageManager.class);
        if (sm == null) {
            return map;
        }
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (v.isPrimary()) {
                continue;
            }
            String state = v.getState();
            if (!Environment.MEDIA_MOUNTED.equals(state)
                    && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
                continue;
            }
            String uuid = v.getUuid();
            File dir = volumeDir(v);
            if (uuid == null || dir == null || !dir.isDirectory()) {
                continue;
            }
            map.put(uuid, new Volume(v.getDescription(ctx), dir));
        }
        return map;
    }

    private static File volumeDir(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= 30) {
            return v.getDirectory();
        }
        try {
            String path = (String) StorageVolume.class.getMethod("getPath").invoke(v);
            return path == null ? null : new File(path);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Some apps (e.g. Lemuroid "browse") join the ID twice, as parent + child:
     * "primary:A/primary:A/x" -> "primary:A/x". The last volume prefix at a path-segment
     * boundary starts the real ID. Only known volume keys count, so a file name that
     * happens to contain a colon is left alone.
     */
    private String normalize(String id) {
        if (id == null) {
            return null;
        }
        int best = 0;
        for (String key : volumes().keySet()) {
            String prefix = key + ":";
            int i = id.lastIndexOf(prefix);
            while (i > 0 && id.charAt(i - 1) != '/') {
                i = id.lastIndexOf(prefix, i - 1);
            }
            best = Math.max(best, i);
        }
        return id.substring(best);
    }

    /** "primary:" / "1234-ABCD:" are volume roots. */
    private static boolean isRoot(String id) {
        return id != null && id.indexOf(':') == id.length() - 1;
    }

    /** Column order must follow the client's projection (PPSSPP reads columns by index). */
    private static String[] columns(String[] projection) {
        return projection != null ? projection : DOC_COLUMNS;
    }

    /** The storage root can't be deleted, moved or renamed. */
    private static void rejectRoot(String documentId) throws FileNotFoundException {
        if (isRoot(documentId)) {
            throw new FileNotFoundException("The root cannot be modified");
        }
    }

    /** Parent ID + name = child ID. No separator is added after a volume root ("primary:"). */
    private static String childId(String parentId, String name) {
        if (parentId.endsWith(":") || parentId.endsWith("/")) {
            return parentId + name;
        }
        return parentId + "/" + name;
    }

    /** Maps an ID to a file and refuses to leave its volume. */
    private File resolve(String documentId) throws FileNotFoundException {
        int colon = documentId == null ? -1 : documentId.indexOf(':');
        Volume volume = colon < 0 ? null : volumes().get(documentId.substring(0, colon));
        if (volume == null) {
            throw new FileNotFoundException("Invalid document: " + documentId);
        }
        String rel = documentId.substring(colon + 1);
        File base = volume.dir;
        File file = rel.isEmpty() ? base : new File(base, rel);
        try {
            String basePath = base.getCanonicalPath();
            String canon = file.getCanonicalPath();
            if (!canon.equals(basePath) && !canon.startsWith(basePath + File.separator)) {
                throw new FileNotFoundException("Outside the root: " + documentId);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        if (!file.exists()) {
            throw new FileNotFoundException("Not found: " + documentId);
        }
        return file;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(ROOT_COLUMNS);
        for (Map.Entry<String, Volume> e : volumes().entrySet()) {
            cursor.newRow()
                    .add(Root.COLUMN_ROOT_ID, e.getKey())
                    .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_CREATE)
                    .add(Root.COLUMN_TITLE, e.getValue().label)
                    .add(Root.COLUMN_DOCUMENT_ID, e.getKey() + ":");
        }
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        documentId = normalize(documentId);
        MatrixCursor cursor = new MatrixCursor(columns(projection));
        if (TOP.equals(documentId)) {
            addRow(cursor, TOP, getContext().getString(R.string.picker_root_title),
                    Document.MIME_TYPE_DIR, null, 0, 0);
            return cursor;
        }
        includeFile(cursor, documentId, resolve(documentId));
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        parentDocumentId = normalize(parentDocumentId);
        MatrixCursor cursor = new MatrixCursor(columns(projection));

        if (TOP.equals(parentDocumentId)) {
            for (Map.Entry<String, Volume> e : volumes().entrySet()) {
                addRow(cursor, e.getKey() + ":", e.getValue().label, Document.MIME_TYPE_DIR, null,
                        e.getValue().dir.lastModified(), Document.FLAG_DIR_SUPPORTS_CREATE);
            }
            return cursor;
        }

        File parent = resolve(parentDocumentId);

        File[] children = parent.listFiles();
        if (children == null) {
            return cursor;
        }

        // Folders first, then files; each group sorted alphabetically.
        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (File child : children) {
            if (child.getName().startsWith(".")) {
                continue;
            }
            includeFile(cursor, childId(parentDocumentId, child.getName()), child);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        documentId = normalize(documentId);
        File file = resolve(documentId);
        android.util.Log.d(TAG, "open " + file + " mode=" + mode);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        parentDocumentId = normalize(parentDocumentId);
        File parent = resolve(parentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("Not a directory: " + parentDocumentId);
        }

        String safe = displayName == null ? "" : displayName.replace('/', '_').replace('\0', '_').trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) {
            safe = "new_file";
        }

        boolean dir = Document.MIME_TYPE_DIR.equals(mimeType);
        String id = childId(parentDocumentId, safe);
        File target = new File(parent, safe);

        // Return the existing document if there is one (apps often fail to find a file, then create it).
        if (target.exists()) {
            android.util.Log.d(TAG, "createDocument reuse " + target + " dir=" + dir);
            return id;
        }

        boolean ok;
        try {
            ok = dir ? target.mkdirs() : target.createNewFile();
        } catch (IOException e) {
            android.util.Log.w(TAG, "createDocument IO error " + target, e);
            throw new FileNotFoundException(e.getMessage());
        }
        android.util.Log.d(TAG, "createDocument " + target + " dir=" + dir + " ok=" + ok);
        if (!ok) {
            throw new FileNotFoundException("Could not create: " + target);
        }
        return id;
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        documentId = normalize(documentId);
        rejectRoot(documentId);
        File src = resolve(documentId);
        String safe = displayName == null ? "" : displayName.replace('/', '_').replace('\0', '_').trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) {
            throw new FileNotFoundException("Invalid name: " + displayName);
        }
        File dst = new File(src.getParentFile(), safe);
        if (!dst.equals(src)) {
            // PPSSPP writes save states as .tmp and renames over the final file, so overwrite the target.
            if (dst.exists()) {
                deleteRecursive(dst);
            }
            if (!src.renameTo(dst)) {
                throw new FileNotFoundException("Could not rename: " + src);
            }
        }
        android.util.Log.d(TAG, "renameDocument " + src + " -> " + dst);
        int slash = documentId.lastIndexOf('/');
        return slash < 0
                ? documentId.substring(0, documentId.indexOf(':') + 1) + safe
                : documentId.substring(0, slash + 1) + safe;
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        documentId = normalize(documentId);
        rejectRoot(documentId);
        File file = resolve(documentId);
        deleteRecursive(file);
        android.util.Log.d(TAG, "deleteDocument " + file);
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId,
                               String targetParentDocumentId) throws FileNotFoundException {
        sourceDocumentId = normalize(sourceDocumentId);
        targetParentDocumentId = normalize(targetParentDocumentId);
        rejectRoot(sourceDocumentId);
        File src = resolve(sourceDocumentId);
        File dstParent = resolve(targetParentDocumentId);
        if (!dstParent.isDirectory()) {
            throw new FileNotFoundException("Target is not a directory");
        }
        File dst = new File(dstParent, src.getName());
        if (dst.exists()) {
            throw new FileNotFoundException("Hedefte zaten var: " + dst.getName());
        }
        try {
            if (!src.renameTo(dst)) {
                copyRecursive(src, dst);
                deleteRecursive(src);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        android.util.Log.d(TAG, "moveDocument " + src + " -> " + dst);
        return childId(targetParentDocumentId, dst.getName());
    }

    @Override
    public String copyDocument(String sourceDocumentId, String targetParentDocumentId)
            throws FileNotFoundException {
        sourceDocumentId = normalize(sourceDocumentId);
        targetParentDocumentId = normalize(targetParentDocumentId);
        File src = resolve(sourceDocumentId);
        File dstParent = resolve(targetParentDocumentId);
        if (!dstParent.isDirectory()) {
            throw new FileNotFoundException("Target is not a directory");
        }
        File dst = uniqueFile(dstParent, src.getName());
        try {
            copyRecursive(src, dst);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        android.util.Log.d(TAG, "copyDocument " + src + " -> " + dst);
        return childId(targetParentDocumentId, dst.getName());
    }

    /** Is the document under the parent? (Used for tree grant checks.) */
    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        if (parentDocumentId == null || documentId == null) {
            return false;
        }
        String p = (parentDocumentId.endsWith("/") || parentDocumentId.endsWith(":"))
                ? parentDocumentId
                : parentDocumentId + "/";
        return documentId.startsWith(p);
    }

    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException("Could not create directory: " + dst);
            }
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) {
                    copyRecursive(c, new File(dst, c.getName()));
                }
            }
        } else {
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }

    private static File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) {
            return candidate;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; ; i++) {
            candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
    }

    private void includeFile(MatrixCursor cursor, String docId, File file) {
        boolean root = isRoot(docId);
        String name = root ? volumes().get(docId.substring(0, docId.length() - 1)).label : file.getName();
        boolean dir = file.isDirectory();
        String mime = dir ? Document.MIME_TYPE_DIR : mimeOf(file);
        int flags = (root ? 0 : Document.FLAG_SUPPORTS_MOVE | Document.FLAG_SUPPORTS_COPY
                | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_DELETE)
                | (dir ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE);
        addRow(cursor, docId, name, mime, file.isFile() ? file.length() : null, file.lastModified(), flags);
    }

    /** Adds one row in the cursor's own column order (clients may request any projection). */
    private static void addRow(MatrixCursor cursor, String docId, String name, String mime,
                               Long size, long lastModified, int flags) {
        Map<String, Object> values = new java.util.HashMap<>();
        values.put(Document.COLUMN_DOCUMENT_ID, docId);
        values.put(Document.COLUMN_DISPLAY_NAME, name);
        values.put(Document.COLUMN_MIME_TYPE, mime);
        values.put(Document.COLUMN_SIZE, size);
        values.put(Document.COLUMN_LAST_MODIFIED, lastModified);
        values.put(Document.COLUMN_FLAGS, flags);

        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : cursor.getColumnNames()) {
            row.add(values.get(col));
        }
    }

    private static String mimeOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }
}
