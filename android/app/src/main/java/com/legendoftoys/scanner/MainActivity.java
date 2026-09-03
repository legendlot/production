package com.legendoftoys.scanner;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/**
 * LOT Scanner shell.
 *
 * The ENTIRE job of this app is to give the existing scanner PWA a device identity that the
 * browser cannot provide and nothing on the phone can quietly erase. It renders no UI of its
 * own, holds no business logic, and ships no libraries — so there is almost nothing in here
 * that can break, and the scanner keeps updating over the air inside the WebView exactly as it
 * does today. An APK rollout is a one-time cost, not a release channel.
 *
 * Why this exists at all: the web build stored its signing key in IndexedDB, which is
 * best-effort storage the browser may evict while leaving localStorage intact. That is the
 * failure we measured — device code present, key gone, every call unsigned, and the phone
 * still reporting itself as ATT-GATE. A hardware-backed key cannot be evicted that way.
 */
public class MainActivity extends Activity {

    /** The PWA this shell wraps. Navigation is pinned to this host — see shouldOverrideUrlLoading. */
    private static final String START_URL = "https://scanner.legendoftoys.com/";
    private static final String ALLOWED_HOST = "scanner.legendoftoys.com";

    private static final String KEY_ALIAS = "lot_device_key";
    private static final int REQ_CAMERA = 1;

    private WebView web;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // The gate phone and the station scanners sit on a bench all shift. A screen that sleeps
        // mid-queue reads to the floor as "the scanner is broken".
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // getUserMedia must start without a tap — the scanner opens the camera on screen entry.
        s.setMediaPlaybackRequiresUserGesture(false);
        // A server-side marker so an APK install is distinguishable from a plain browser even
        // before any bridge call happens. Cheap, and it makes "who is on the app" answerable
        // from request logs alone.
        s.setUserAgentString(s.getUserAgentString() + " LOTScanner/1.0");

        // ⛔ addJavascriptInterface exposes this object to EVERY page the WebView loads, so the
        // WebView must never be allowed to load a page we do not control. Without this pin, any
        // off-host navigation could read the device identity.
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest rq) {
                Uri u = rq.getUrl();
                if (ALLOWED_HOST.equals(u.getHost())) return false;   // ours: load it
                return true;                                          // anything else: refuse
            }
        });

        // Grant the WebView's camera request. The runtime CAMERA grant below is the real gate;
        // this only forwards it to the page.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        web.addJavascriptInterface(new Bridge(), "LOTDevice");

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }

        web.loadUrl(START_URL);
    }

    /** Back walks the PWA's own history rather than dropping the operator out of the app. */
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /**
     * The bridge. Three methods, no state, no flow logic — the web app decides what to do with
     * what it gets back. Reachable from the page as window.LOTDevice.
     */
    public class Bridge {

        /**
         * The stable identity, and the whole point of the app.
         *
         * ANDROID_ID is scoped to (app signing key, user, device): it survives reboots, app
         * updates and reinstalls of THIS app, and changes only on a factory reset. No browser
         * API exposes anything equivalent — every web identifier is either clearable site data
         * or non-unique.
         *
         * ⚠️ It is an IDENTIFIER, not a credential. Sent as a plain field it is spoofable by
         * anyone who reads the page source, which is fine while we are only OBSERVING which
         * phones exist. Do not build an enforcement gate on deviceId() alone — use sign().
         */
        @JavascriptInterface
        public String deviceId() {
            return Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        }

        /**
         * SPKI public key, base64 — the same shape completeDeviceEnrolment already accepts, so
         * registration needs no new server contract.
         *
         * Dormant in phase 1. It exists now only so that turning enforcement on later does not
         * mean re-flashing every handset; generating it costs nothing until it is called.
         */
        @JavascriptInterface
        public String publicKey() {
            try {
                Certificate c = ensureKey();
                return Base64.encodeToString(c.getPublicKey().getEncoded(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        /**
         * Sign a canonical string with the hardware-backed key. Returns base64, or "" on any
         * failure — the caller decides whether to proceed, exactly as the web path does today.
         *
         * ⚠️ INCOMPATIBILITY TO HANDLE BEFORE USING THIS: SHA256withECDSA here emits a
         * DER-encoded (r,s) signature, while WebCrypto's ECDSA emits raw r||s. The worker's
         * verifyDeviceSignature was written against the WebCrypto shape, so it must learn the
         * DER form (or this must convert) before any signed call is switched on. Noted here
         * rather than discovered as a silent verification failure later.
         */
        @JavascriptInterface
        public String sign(String canonical) {
            try {
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                ensureKey();
                PrivateKey pk = (PrivateKey) ks.getKey(KEY_ALIAS, null);
                Signature sig = Signature.getInstance("SHA256withECDSA");
                sig.initSign(pk);
                sig.update(canonical.getBytes("UTF-8"));
                return Base64.encodeToString(sig.sign(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }
    }

    /**
     * Create the signing key on first use and return its certificate. The private key is
     * generated INSIDE the AndroidKeyStore and is never extractable — it cannot be copied to
     * another phone, and clearing the browser's site data does not touch it.
     */
    private Certificate ensureKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator kpg =
                    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            kpg.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build());
            kpg.generateKeyPair();
        }
        return ks.getCertificate(KEY_ALIAS);
    }
}
