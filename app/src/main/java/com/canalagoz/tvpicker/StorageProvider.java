package com.canalagoz.tvpicker;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
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

/**
 * Sistem DocumentsUI olmayan cihazlar için kendi SAF sağlayıcımız.
 *
 * Belge kimlikleri "primary:" öneki + depolama köküne göre göreli yol biçimindedir
 * (örn. "primary:Download/psp"). ExternalStorageProvider ile aynı biçim. PPSSPP gibi
 * uygulamalar kimlikleri yol gibi birleştirdiği için mutlak yol kullanmak onları bozuyordu.
 */
public class StorageProvider extends DocumentsProvider {

    public static final String AUTHORITY = "com.canalagoz.tvpicker.docs";
    private static final String ROOT_ID = "storage";
    private static final String PREFIX = "primary:";
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

    /**
     * Bazı uygulamalar (örn. Lemuroid "gözat") kimliği üst + alt olarak iki kez birleştiriyor:
     * "primary:A/primary:A/x" -> "primary:A/x". Son "primary:" öneki gerçek kimliktir.
     */
    private static String normalize(String id) {
        if (id == null) {
            return null;
        }
        int last = id.lastIndexOf(PREFIX);
        return last > 0 ? id.substring(last) : id;
    }

    /** İstemci projection verdiyse sütun sırası ona göre olmalı (PPSSPP indeksle okuyor). */
    private static String[] columns(String[] projection) {
        return projection != null ? projection : DOC_COLUMNS;
    }

    private File baseDir() {
        return Environment.getExternalStorageDirectory();
    }

    /** Depolama kökü silinemez, taşınamaz, yeniden adlandırılamaz. */
    private static void rejectRoot(String documentId) throws FileNotFoundException {
        if (PREFIX.equals(documentId)) {
            throw new FileNotFoundException("Kök değiştirilemez");
        }
    }

    /** Üst kimlik + ad = alt kimlik. Kök ("primary:") için ayraç eklenmez. */
    private static String childId(String parentId, String name) {
        if (parentId.endsWith(":") || parentId.endsWith("/")) {
            return parentId + name;
        }
        return parentId + "/" + name;
    }

    /** Kimliği dosyaya çevirir. Kökün dışına çıkmayı engeller. */
    private File resolve(String documentId) throws FileNotFoundException {
        if (documentId == null || !documentId.startsWith(PREFIX)) {
            throw new FileNotFoundException("Geçersiz belge: " + documentId);
        }
        String rel = documentId.substring(PREFIX.length());
        File base = baseDir();
        File file = rel.isEmpty() ? base : new File(base, rel);
        try {
            String basePath = base.getCanonicalPath();
            String canon = file.getCanonicalPath();
            if (!canon.equals(basePath) && !canon.startsWith(basePath + File.separator)) {
                throw new FileNotFoundException("Kök dışında: " + documentId);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        if (!file.exists()) {
            throw new FileNotFoundException("Bulunamadı: " + documentId);
        }
        return file;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(ROOT_COLUMNS);
        cursor.newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_CREATE)
                .add(Root.COLUMN_TITLE, "Depolama")
                .add(Root.COLUMN_DOCUMENT_ID, PREFIX);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        documentId = normalize(documentId);
        MatrixCursor cursor = new MatrixCursor(columns(projection));
        includeFile(cursor, documentId, resolve(documentId));
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        parentDocumentId = normalize(parentDocumentId);
        File parent = resolve(parentDocumentId);
        MatrixCursor cursor = new MatrixCursor(columns(projection));

        File[] children = parent.listFiles();
        if (children == null) {
            return cursor;
        }

        // Önce klasörler, sonra dosyalar; her grup kendi içinde alfabetik.
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
            throw new FileNotFoundException("Klasör değil: " + parentDocumentId);
        }

        String safe = displayName == null ? "" : displayName.replace('/', '_').replace('\0', '_').trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) {
            safe = "yeni_dosya";
        }

        boolean dir = Document.MIME_TYPE_DIR.equals(mimeType);
        String id = childId(parentDocumentId, safe);
        File target = new File(parent, safe);

        // Zaten varsa aynısını döndür (uygulamalar önce bulamayıp sonra oluşturmayı dener).
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
            throw new FileNotFoundException("Oluşturulamadı: " + target);
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
            throw new FileNotFoundException("Geçersiz ad: " + displayName);
        }
        File dst = new File(src.getParentFile(), safe);
        if (!dst.equals(src)) {
            // PPSSPP kayıt durumunu .tmp olarak yazıp üzerine yeniden adlandırıyor; hedef varsa ezilir.
            if (dst.exists()) {
                deleteRecursive(dst);
            }
            if (!src.renameTo(dst)) {
                throw new FileNotFoundException("Yeniden adlandırılamadı: " + src);
            }
        }
        android.util.Log.d(TAG, "renameDocument " + src + " -> " + dst);
        int slash = documentId.lastIndexOf('/');
        return slash < 0 ? PREFIX + safe : documentId.substring(0, slash + 1) + safe;
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
            throw new FileNotFoundException("Hedef klasör değil");
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
            throw new FileNotFoundException("Hedef klasör değil");
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

    /** Alt kimlik, üst kimliğin altında mı? (Tree grant kontrolü için.) */
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
                throw new IOException("Klasör oluşturulamadı: " + dst);
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
        String name = docId.equals(PREFIX) ? "Depolama" : file.getName();
        boolean dir = file.isDirectory();
        String mime = dir ? Document.MIME_TYPE_DIR : mimeOf(file);
        int flags = (docId.equals(PREFIX) ? 0 : Document.FLAG_SUPPORTS_MOVE | Document.FLAG_SUPPORTS_COPY
                | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_DELETE)
                | (dir ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE);

        java.util.Map<String, Object> values = new java.util.HashMap<>();
        values.put(Document.COLUMN_DOCUMENT_ID, docId);
        values.put(Document.COLUMN_DISPLAY_NAME, name);
        values.put(Document.COLUMN_MIME_TYPE, mime);
        values.put(Document.COLUMN_SIZE, file.isFile() ? file.length() : null);
        values.put(Document.COLUMN_LAST_MODIFIED, file.lastModified());
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
        String ext = name.substring(dot + 1).toLowerCase();
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }
}
