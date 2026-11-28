package cn.alittlecookie.lutista.tether.transfer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.DocumentsContract;

/** SDK-only grant broker: a standalone test-APK process has no target Kotlin classloader. */
public final class SafGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String target = getIntent().getStringExtra("target");
        String root = getIntent().getStringExtra("root");
        String nonce = getIntent().getStringExtra("nonce");
        if (target == null || root == null || nonce == null) {
            throw new IllegalArgumentException("Missing grant arguments");
        }
        SafRegressionDocumentsProvider.createRoot(this, root);
        Uri tree = DocumentsContract.buildTreeDocumentUri(SafRegressionDocumentsProvider.AUTHORITY, root);
        int access = getIntent().getIntExtra("access", 0);
        if (access != 0) {
            grantUriPermission(target, tree, access | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        sendBroadcast(new Intent(SafRegressionDocumentsProvider.GRANTED).setPackage(target)
                .putExtra("nonce", nonce).putExtra("uri", tree.toString()).putExtra("uid", Process.myUid()));
        finish();
    }
}
