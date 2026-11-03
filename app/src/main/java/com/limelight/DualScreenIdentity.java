package com.limelight;

import android.content.Context;
import android.content.ContextWrapper;

import com.limelight.binding.PlatformBinding;
import com.limelight.computers.IdentityManager;
import com.limelight.nvstream.http.LimelightCryptoProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

/**
 * The second screen's stream is a device of its own to the PC: a host that lets two devices stream at
 * once tells them apart by their unique ID and paired certificate, and takes a second stream from the
 * same device for the first one starting again. So the second stream has an ID and a certificate of
 * its own, kept in a folder beside the first's and paired with the PC once, like another install.
 * <p>
 * A context whose files live in that folder gives {@link IdentityManager} and the crypto provider
 * their second identity without either of them knowing.
 */
final class DualScreenIdentity extends ContextWrapper {
    static final String FOLDER = "screen2";

    private final File folder;

    DualScreenIdentity(Context base) {
        super(base.getApplicationContext());
        folder = new File(base.getApplicationContext().getFilesDir(), FOLDER);
        //noinspection ResultOfMethodCallIgnored
        folder.mkdirs();
    }

    @Override
    public File getFilesDir() {
        return folder;
    }

    @Override
    public FileInputStream openFileInput(String name) throws FileNotFoundException {
        return new FileInputStream(new File(folder, name));
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        return new FileOutputStream(new File(folder, name));
    }

    String uniqueId() {
        return new IdentityManager(this).getUniqueId();
    }

    LimelightCryptoProvider crypto() {
        return PlatformBinding.getCryptoProvider(this);
    }
}
