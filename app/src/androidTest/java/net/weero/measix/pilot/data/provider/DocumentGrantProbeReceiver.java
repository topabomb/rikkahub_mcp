package net.weero.measix.pilot.data.provider;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Android/JDK-only so the separate test APK process does not need the target's class loader. */
public final class DocumentGrantProbeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle result = new Bundle();
        result.putInt("uid", Process.myUid());
        result.putInt("pid", Process.myPid());
        result.putString("package", context.getPackageName());
        try {
            Uri uri = Uri.parse(intent.getStringExtra("uri"));
            if (intent.getBooleanExtra("write", false)) {
                // Opening rw checks write permission without truncating or changing the fixture.
                try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "rw")) {
                    if (descriptor == null) throw new IllegalStateException("Provider returned no descriptor");
                }
            } else {
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalStateException("Provider returned no input stream");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (bytes.size() + count > 8192) throw new IllegalStateException("Probe input exceeds 8192 bytes");
                        bytes.write(buffer, 0, count);
                    }
                    result.putByteArray("bytes", bytes.toByteArray());
                }
            }
            result.putBoolean("opened", true);
        } catch (Exception failure) {
            result.putString("errorType", failure.getClass().getName());
            result.putString("errorMessage", failure.getMessage());
        }
        setResultExtras(result);
    }
}
