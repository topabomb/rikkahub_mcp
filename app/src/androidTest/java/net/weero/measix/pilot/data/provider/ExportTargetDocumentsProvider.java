package net.weero.measix.pilot.data.provider;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;

/** A flat, test-owned tree in the test APK UID. Android enforces all caller URI grants. */
public final class ExportTargetDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "net.weero.measix.pilot.test.exporttarget";
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};

    static File directory(Context context, String run) {
        if (!UUID.fromString(run).toString().equals(run)) throw new IllegalArgumentException("Invalid fixture ID");
        return new File(new File(context.getFilesDir(), "export-target-fixtures"), run);
    }

    static File document(Context context, String id) throws FileNotFoundException {
        String[] parts = id.split("/", -1);
        if (parts.length < 1 || parts.length > 2) throw new FileNotFoundException("Invalid fixture document");
        File root = directory(context, parts[0]);
        File file = parts.length == 1 ? root : new File(root, validName(parts[1]));
        if (!file.exists()) throw new FileNotFoundException(id);
        return file;
    }

    private static String validName(String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Invalid fixture name");
        }
        return name;
    }

    @Override public boolean onCreate() { return true; }

    // The fixture is addressed by its exact tree URI, never offered in the user's picker.
    @Override public Cursor queryRoots(String[] projection) {
        return new MatrixCursor(projection != null ? projection : new String[]{"root_id"});
    }

    private void row(MatrixCursor cursor, String id, File file) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                case Document.COLUMN_DISPLAY_NAME: row.add(file.getName()); break;
                case Document.COLUMN_MIME_TYPE:
                    row.add(file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream"); break;
                case Document.COLUMN_FLAGS:
                    row.add(file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE :
                            Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE); break;
                case Document.COLUMN_SIZE: row.add(file.isDirectory() ? 0 : file.length()); break;
                case "fixture_uid": row.add(android.os.Process.myUid()); break;
                case "fixture_pid": row.add(android.os.Process.myPid()); break;
                default: row.add(null);
            }
        }
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : COLUMNS);
        row(result, id, document(getContext(), id));
        return result;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder)
            throws FileNotFoundException {
        File root = document(getContext(), parent);
        File[] files = root.listFiles();
        if (files == null) throw new FileNotFoundException(parent);
        MatrixCursor result = new MatrixCursor(projection != null ? projection : COLUMNS);
        for (File file : files) row(result, parent + "/" + file.getName(), file);
        return result;
    }

    @Override public boolean isChildDocument(String parent, String child) {
        try {
            document(getContext(), parent);
            document(getContext(), child);
            return child.startsWith(parent + "/") && child.indexOf('/', parent.length() + 1) < 0;
        } catch (FileNotFoundException missing) {
            return false;
        }
    }

    @Override public synchronized String createDocument(String parent, String mimeType, String displayName)
            throws FileNotFoundException {
        File root = document(getContext(), parent);
        if (parent.contains("/") || !root.isDirectory()) throw new FileNotFoundException(parent);
        String name = validName(displayName);
        try {
            File target = new File(root, name);
            int index = 1;
            while (!target.createNewFile()) target = new File(root, name + " (" + index++ + ")");
            return parent + "/" + target.getName();
        } catch (IOException failure) {
            FileNotFoundException error = new FileNotFoundException(failure.getMessage());
            error.initCause(failure);
            throw error;
        }
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = document(getContext(), id);
        if (!file.isFile()) throw new FileNotFoundException(id);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public void deleteDocument(String id) throws FileNotFoundException {
        File file = document(getContext(), id);
        if (!id.contains("/") || !file.isFile() || !file.delete()) throw new FileNotFoundException(id);
    }
}
