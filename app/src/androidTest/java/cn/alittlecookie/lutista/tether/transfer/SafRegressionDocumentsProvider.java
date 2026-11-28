package cn.alittlecookie.lutista.tether.transfer;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** SDK-only provider fixture, running under the distinct test-APK UID. */
public final class SafRegressionDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "cn.alittlecookie.lutista.test.saf-regression";
    public static final String GRANTED = "cn.alittlecookie.lutista.test.SAF_GRANTED";
    private static final String[] ROOT_COLUMNS = {
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
        Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES
    };
    private static final String[] DOCUMENT_COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_FLAGS
    };

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? ROOT_COLUMNS : projection;
        MatrixCursor out = new MatrixCursor(columns);
        File[] roots = base(getContext()).listFiles();
        if (roots != null) for (File root : roots) {
            Map<String, Object> values = new HashMap<>();
            values.put(Root.COLUMN_ROOT_ID, root.getName());
            values.put(Root.COLUMN_DOCUMENT_ID, root.getName());
            values.put(Root.COLUMN_TITLE, "SAF regression");
            values.put(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE);
            // Unknown capacity is allowed by the DocumentsProvider contract.
            values.put(Root.COLUMN_AVAILABLE_BYTES, null);
            add(out, columns, values);
        }
        return out;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        String[] columns = projection == null ? DOCUMENT_COLUMNS : projection;
        MatrixCursor out = new MatrixCursor(columns);
        append(out, columns, id, file(id));
        return out;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) throws FileNotFoundException {
        String[] columns = projection == null ? DOCUMENT_COLUMNS : projection;
        MatrixCursor out = new MatrixCursor(columns);
        File[] children = file(parent).listFiles();
        if (children != null) for (File child : children) {
            append(out, columns, parent + "/" + child.getName(), child);
        }
        return out;
    }

    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new FileNotFoundException("Invalid name");
        }
        File destination = new File(file(parent), name);
        try {
            boolean created = Document.MIME_TYPE_DIR.equals(mime) ? destination.mkdir() : destination.createNewFile();
            if (!created) throw new FileNotFoundException("Test document already exists");
        } catch (IOException failure) {
            throw new FileNotFoundException(failure.toString());
        }
        return parent + "/" + name;
    }

    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (!file(id).delete()) throw new FileNotFoundException("Test document deletion failed");
    }

    @Override public boolean isChildDocument(String parent, String child) {
        return child.startsWith(parent + "/");
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (id.startsWith("fail-") && mode.indexOf('w') >= 0) {
            throw new FileNotFoundException("Injected provider write failure");
        }
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }

    private File file(String id) throws FileNotFoundException {
        try {
            File base = base(getContext()).getCanonicalFile();
            File result = new File(base, id).getCanonicalFile();
            if (!result.getPath().startsWith(base.getPath() + File.separator)) {
                throw new FileNotFoundException("Document outside root");
            }
            return result;
        } catch (IOException failure) {
            throw new FileNotFoundException(failure.toString());
        }
    }

    private static File base(Context context) {
        File base = new File(context.getFilesDir(), "saf-regression");
        if (!base.isDirectory() && !base.mkdirs()) throw new IllegalStateException("Cannot create fixture directory");
        return base;
    }

    public static void createRoot(Context context, String id) {
        if (!id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid root");
        if (!new File(base(context), id).mkdir()) throw new IllegalStateException("Test root already exists");
    }

    private static void append(MatrixCursor out, String[] columns, String id, File file) {
        Map<String, Object> values = new HashMap<>();
        values.put(Document.COLUMN_DOCUMENT_ID, id);
        values.put(Document.COLUMN_DISPLAY_NAME, file.getName());
        values.put(Document.COLUMN_MIME_TYPE, file.isDirectory() ? Document.MIME_TYPE_DIR : "image/jpeg");
        values.put(Document.COLUMN_SIZE, file.length());
        int flags = Document.FLAG_SUPPORTS_DELETE | (file.isDirectory()
                ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE);
        values.put(Document.COLUMN_FLAGS, flags);
        add(out, columns, values);
    }

    private static void add(MatrixCursor out, String[] columns, Map<String, Object> values) {
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) row[i] = values.get(columns[i]);
        out.addRow(row);
    }
}
