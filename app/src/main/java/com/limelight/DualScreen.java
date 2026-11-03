package com.limelight;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Process;
import android.view.Display;
import android.widget.Toast;

import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.NavigationDialogBuilder;
import com.limelight.utils.LaunchSource;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Dual-screen mode. On a device with a second screen, the stream on the first screen is joined by a
 * second stream of the same game, shown full screen on the second screen at a resolution of its own
 * (the Second screen resolution setting). The second one is {@link GameSecondary}: a device of its
 * own to the PC, which plays no sound and takes no controller or microphone.
 * Which PC screen it shows is up to the host: a host that gives each stream a display of its own
 * shows two screens; one that captures a single display shows it on both.
 */
final class DualScreen {
    /** On the second stream's intent. */
    static final String EXTRA_SECOND_SCREEN = "nightjar.secondScreen";
    /** The first stream's end, for the second to follow. Sent to this app only. */
    static final String ACTION_END = "com.limelight.DUAL_SCREEN_END";
    /** The touch mode changed in one stream, for the other to follow. Sent to this app only. */
    static final String ACTION_TOUCH_MODE = "com.limelight.DUAL_SCREEN_TOUCH_MODE";
    private static final String EXTRA_TOUCH_MODE = "touchMode";
    private static final String EXTRA_FROM_PID = "fromPid";

    private DualScreen() { }

    static boolean isSecond(Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_SECOND_SCREEN, false);
    }

    /**
     * The first screen that is not {@code mainId} and can show a window, or -1. Like Artemis, any
     * other display counts, because a dual-screen device's second panel is an internal display that
     * Android does not list as a presentation display; private displays and ones going away do not.
     */
    static int pickSecondDisplayId(int[] ids, boolean[] usable, int mainId) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != mainId && usable[i]) {
                return ids[i];
            }
        }
        return -1;
    }

    static Display secondDisplay(Context context, int mainId) {
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) {
            return null;
        }
        Display[] displays = manager.getDisplays();
        int[] ids = new int[displays.length];
        boolean[] usable = new boolean[displays.length];
        for (int i = 0; i < displays.length; i++) {
            ids[i] = displays[i].getDisplayId();
            usable[i] = displays[i].isValid() && (displays[i].getFlags() & Display.FLAG_PRIVATE) == 0;
        }
        int id = pickSecondDisplayId(ids, usable, mainId);
        return id < 0 ? null : manager.getDisplay(id);
    }

    /**
     * Starts the second stream once, as soon as the host has accepted the first stream's launch (see
     * Game.stageComplete): the game is then running, so the second stream resumes it where the PC offers
     * that. It need not wait for the first stream's handshake and first frame, but it must not start
     * before the launch, or both would launch the game at once. The second stream is a device of its
     * own to the PC (see {@link DualScreenIdentity}), so the first time on a PC it is paired, with a
     * PIN the way any device is, before it starts. Like any device it then resumes the running game
     * if the PC offers it, or starts its own session of the game if it does not.
     */
    static void start(Game game) {
        if (game.dualScreenStarted || isSecond(game.getIntent()) || !game.prefConfig.dualScreen) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        if (game.app.getRole() != NvApp.Role.STREAM || game.externalPresentation != null) {
            return;
        }
        Display second = secondDisplay(game, game.getWindowManager().getDefaultDisplay().getDisplayId());
        if (second == null) {
            LimeLog.info("Dual screen: this device has no second screen");
            return;
        }
        // The game that is running, whichever tile started the first stream. A tile that resumes or
        // switches ("Resume") names it only once the host has said which; too early, the later call finds it.
        boolean named = game.app.getControl() == NvApp.Control.NONE;
        int appId = named ? game.app.getAppId() : game.conn.getHostDetails().runningGameId;
        String appUuid = named ? game.app.getAppUuid() : game.conn.getHostDetails().runningGameUuid;
        if (appId == 0 && (appUuid == null || appUuid.isEmpty())) {
            return;
        }
        game.dualScreenStarted = true;
        link(game);
        Intent intent = new Intent(game.getIntent()).setClass(game, GameSecondary.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_SECOND_SCREEN, true)
                .putExtra(Game.EXTRA_APP_ID, appId)
                .putExtra(Game.EXTRA_APP_UUID, appUuid);
        intent.removeExtra(Game.EXTRA_REPLACED_APP_NAME);
        intent.removeExtra(GameVideoOutput.EXTRA_OUTPUT_DISPLAY);
        LaunchSource.put(intent, LaunchSource.DUAL_SCREEN);
        int displayId = second.getDisplayId();
        new Thread(() -> pairThenLaunch(game, intent, displayId), "DualScreen").start();
    }

    private static void pairThenLaunch(Game game, Intent intent, int displayId) {
        try {
            DualScreenIdentity identity = new DualScreenIdentity(game);
            String uniqueId = identity.uniqueId();
            NvHTTP http = new NvHTTP(
                    new ComputerDetails.AddressTuple(intent.getStringExtra(Game.EXTRA_HOST),
                            intent.getIntExtra(Game.EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT)),
                    intent.getIntExtra(Game.EXTRA_HTTPS_PORT, 0), uniqueId,
                    serverCert(intent.getByteArrayExtra(Game.EXTRA_SERVER_CERT)), identity.crypto());
            if (http.getPairState() != PairingManager.PairState.PAIRED && !pair(game, http)) {
                return;
            }
            intent.putExtra(Game.EXTRA_UNIQUEID, uniqueId);
        } catch (IOException | XmlPullParserException | RuntimeException e) {
            LimeLog.warning("Dual screen: the second stream's identity could not be checked: " + e);
            game.runOnUiThread(() -> game.messages.alert(R.string.dual_screen_pair_failed, Toast.LENGTH_LONG));
            return;
        }
        game.runOnUiThread(() -> launch(game, intent, displayId));
    }

    /** Pairs the second identity, showing the PIN on this screen; false when it was not paired. */
    private static boolean pair(Game game, NvHTTP http) throws IOException, XmlPullParserException {
        String pin = PairingManager.generatePinString();
        Dialog[] dialog = new Dialog[1];
        game.runOnUiThread(() -> {
            if (game.isFinishing() || game.isDestroyed()) {
                return;
            }
            dialog[0] = new NavigationDialogBuilder(game).setTitle(R.string.dual_screen_pair_title)
                    .setMessage(game.getString(R.string.dual_screen_pair_message, pin))
                    .setNegativeButton(android.R.string.cancel, (d, which) -> http.cancelPendingRequests())
                    .setOnCancelListener(d -> http.cancelPendingRequests())
                    .create();
            game.menuActions.showStreamDialog(dialog[0]);
        });
        PairingManager.PairState state;
        try {
            state = http.getPairingManager().pair(http.getServerInfo(true), pin);
        } finally {
            game.runOnUiThread(() -> {
                if (dialog[0] != null) dialog[0].dismiss();
            });
        }
        if (state != PairingManager.PairState.PAIRED) {
            LimeLog.warning("Dual screen: pairing the second stream ended as " + state);
            game.runOnUiThread(() -> game.messages.alert(R.string.dual_screen_pair_failed, Toast.LENGTH_LONG));
            return false;
        }
        return true;
    }

    private static X509Certificate serverCert(byte[] der) {
        try {
            return der == null ? null : (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException e) {
            return null;
        }
    }

    private static void launch(Game game, Intent intent, int displayId) {
        if (game.isFinishing() || game.isDestroyed()) {
            return;
        }
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        try {
            game.startActivity(intent, options.toBundle());
            LimeLog.info("Dual screen: second stream on display " + displayId);
        } catch (RuntimeException e) {
            LimeLog.warning("Dual screen: the second screen would not open: " + e);
            game.messages.alert(R.string.dual_screen_unavailable, Toast.LENGTH_LONG);
        }
    }

    /** The crypto provider of this stream: the second stream pairs, and so identifies, as its own device. */
    static LimelightCryptoProvider cryptoProvider(Game game) {
        return isSecond(game.getIntent()) ? new DualScreenIdentity(game).crypto() : PlatformBinding.getCryptoProvider(game);
    }

    /** The first stream is over: the second goes with it. */
    static void end(Game game) {
        if (game.dualScreenLink != null) {
            try {
                game.unregisterReceiver(game.dualScreenLink);
            } catch (IllegalArgumentException ignored) {
                // Not registered any more.
            }
            game.dualScreenLink = null;
        }
        if (game.dualScreenStarted && !isSecond(game.getIntent()) && game.isFinishing()) {
            game.sendBroadcast(new Intent(ACTION_END).setPackage(game.getPackageName()));
        }
    }

    /**
     * Joins this stream to the other one: the second stream follows the first one's end, and each follows
     * the other's touch mode. Both streams are Game activities in processes of their own, so they talk by
     * broadcasts to this app.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    static void link(Game game) {
        if (game.dualScreenLink != null) {
            return;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_END.equals(intent.getAction())) {
                    if (isSecond(game.getIntent())) {
                        game.finish();
                    }
                    return;
                }
                int mode = intent.getIntExtra(EXTRA_TOUCH_MODE, -1);
                if (mode >= 0 && intent.getIntExtra(EXTRA_FROM_PID, -1) != Process.myPid()
                        && game.touchInput != null && game.externalPresentation == null) {
                    game.touchInput.applyTouchMode(mode, !isSecond(game.getIntent()));
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_END);
        filter.addAction(ACTION_TOUCH_MODE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            game.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            game.registerReceiver(receiver, filter);
        }
        game.dualScreenLink = receiver;
    }

    /** This stream's touch mode changed: the other stream, if there is one, takes the same one. */
    static void mirrorTouchMode(Game game, int mode) {
        if (game.dualScreenLink != null) {
            game.sendBroadcast(new Intent(ACTION_TOUCH_MODE).setPackage(game.getPackageName())
                    .putExtra(EXTRA_TOUCH_MODE, mode).putExtra(EXTRA_FROM_PID, Process.myPid()));
        }
    }

    /**
     * What the second stream gives up to the first: sound, the controllers and the microphone stay
     * with the first stream. Its size is the second screen's own setting (that screen's full size, or
     * a fixed one), whatever the first screen's resolution is.
     */
    static void configureSecond(PreferenceConfiguration config, Display display) {
        if (!config.useResolution(config.secondScreenResolution)) {
            config.followScreen(display);
        }
        config.dualScreen = false;
        config.muteAudio = true;
        config.enableMic = false;
        config.onscreenController = false;
        config.usbDriver = false;
        config.usbDualSense = false;
        config.usbDualSenseHaptics = false;
        config.usbSwitchPro = false;
    }
}
