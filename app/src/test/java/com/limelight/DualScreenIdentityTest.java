package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import com.limelight.binding.PlatformBinding;
import com.limelight.computers.IdentityManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

/** The second screen's stream is a device of its own: its own ID and certificate, kept apart from the first's. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class DualScreenIdentityTest {
    @Test
    public void theSecondIdentityKeepsItsOwnFolderAndSurvivesRestarts() {
        Context app = RuntimeEnvironment.getApplication();
        String id = new DualScreenIdentity(app).uniqueId();
        assertTrue(new File(app.getFilesDir(), DualScreenIdentity.FOLDER).isDirectory());
        assertTrue(new File(new File(app.getFilesDir(), DualScreenIdentity.FOLDER), "uniqueid").isFile());
        assertEquals(id, new DualScreenIdentity(app).uniqueId());
    }

    @Test
    public void theSecondIdentityIsNotTheFirst() {
        Context app = RuntimeEnvironment.getApplication();
        assertNotEquals(new IdentityManager(app).getUniqueId(), new DualScreenIdentity(app).uniqueId());
        assertNotEquals(PlatformBinding.getCryptoProvider(app).getClientCertificate(),
                new DualScreenIdentity(app).crypto().getClientCertificate());
        assertFalse(new File(app.getFilesDir(), "client.key").equals(
                new File(new File(app.getFilesDir(), DualScreenIdentity.FOLDER), "client.key")));
    }
}
