package net.weero.measix.pilot.data.provider;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;

/** JDK/framework only: the test APK process does not load target-app Kotlin or Koin classes. */
public final class ExportTargetControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Bundle result = new Bundle();
        result.putString("package", context.getPackageName());
        result.putInt("uid", Process.myUid());
        result.putInt("pid", Process.myPid());
        try {
            String run = intent.getStringExtra("run");
            File root = ExportTargetDocumentsProvider.directory(context, run);
            Uri tree = DocumentsContract.buildTreeDocumentUri(ExportTargetDocumentsProvider.AUTHORITY, run);
            String target = intent.getStringExtra("targetPackage");
            if (!context.getPackageName().equals(target + ".test") ||
                    context.getPackageManager().getApplicationInfo(target, 0).uid == Process.myUid()) {
                throw new IllegalArgumentException("Not the instrumented fixture application");
            }
            int access = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            switch (intent.getStringExtra("command")) {
                case "setup":
                    if (!root.mkdirs()) throw new IllegalStateException("Fixture already exists");
                    try (FileOutputStream output = new FileOutputStream(new File(root, "first.txt"))) {
                        output.write(intent.getByteArrayExtra("bytes"));
                    }
                    break;
                case "grant":
                    if (!root.isDirectory()) throw new IllegalStateException("Fixture missing");
                    context.grantUriPermission(target, tree, access | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    break;
                case "revoke":
                    context.revokeUriPermission(target, tree, access);
                    break;
                case "inspect":
                    File[] files = root.listFiles();
                    if (files == null) throw new IllegalStateException("Fixture missing");
                    ArrayList<String> ids = new ArrayList<>();
                    for (File file : files) ids.add(run + "/" + file.getName());
                    result.putStringArrayList("ids", ids);
                    String id = intent.getStringExtra("documentId");
                    if (id != null) {
                        if (!id.startsWith(run + "/")) throw new IllegalArgumentException("Foreign fixture document");
                        File file = ExportTargetDocumentsProvider.document(context, id);
                        if (!file.isFile() || file.length() > 8192) throw new IllegalStateException("Probe too large");
                        result.putByteArray("bytes", Files.readAllBytes(file.toPath()));
                    }
                    break;
                case "cleanup":
                    context.revokeUriPermission(target, tree, access);
                    if (root.exists()) {
                        File[] owned = root.listFiles();
                        if (owned == null) throw new IllegalStateException("Cannot list fixture cleanup");
                        for (File file : owned) {
                            if (!file.isFile() || !file.delete()) throw new IllegalStateException("Cannot delete fixture document");
                        }
                        if (!root.delete()) throw new IllegalStateException("Cannot delete fixture tree");
                    }
                    break;
                default: throw new IllegalArgumentException("Unknown fixture command");
            }
            result.putBoolean("success", true);
        } catch (Exception failure) {
            result.putString("errorType", failure.getClass().getName());
            result.putString("errorMessage", failure.getMessage());
        }
        setResultExtras(result);
    }
}
