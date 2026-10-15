package com.simonramstedt.yoke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.Display;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.SecurityException;
import java.lang.StackTraceElement;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;


public class YokeActivity extends Activity implements NsdManager.DiscoveryListener {
    private final String SERVICE_TYPE = "_yoke._udp.";
    private String NOTHING;
    private String ENTER_IP;
    private NsdManager mNsdManager;
    private NsdServiceInfo mService;
    private DatagramSocket mSocket;
    private byte[] vals_buffer = null;
    private byte[] disconnect_pattern = null;
    private final int poll_rate = 20;
    private Map<String, NsdServiceInfo> mServiceMap = new ConcurrentHashMap<>();
    private List<String> mServiceNames = new CopyOnWriteArrayList<>();
    private volatile boolean mNsdActive = false;
    private WifiManager.MulticastLock mMulticastLock;

    // Broadcast discovery: works on networks that filter mDNS multicast.
    // Must match DISCOVERY_PORT in the desktop client (yoke/network.py).
    private static final int DISCOVERY_PORT = 15156;
    private static final int DISCOVERY_INTERVAL_MS = 2000;
    private static final int DISCOVERY_STALE_MS = 7000;
    private static final byte[] DISCOVERY_REQUEST = "YOKE?".getBytes(Charset.forName("UTF-8"));
    private Map<String, String> mBroadcastMap = new ConcurrentHashMap<>(); // name -> "ip:port"
    private Map<String, Long> mBroadcastSeen = new ConcurrentHashMap<>();
    private volatile Thread mBroadcastThread;
    private SharedPreferences sharedPref;
    private TextView mTextView;
    private Spinner mSpinner;
    private volatile boolean mSpinnerAutomatic = false;
    private String oldtgt;
    private ProgressBar mProgressBar;
    private ArrayAdapter<String> mAdapter;
    private Handler handler;
    private WebView wv;
    private Resources res;
    private File currentJoypadPath;
    private File currentMainPath;
    private File currentManifestPath;
    private File futureJoypadPath;
    private File futureMainPath;
    private File futureManifestPath;
    private String currentHost = null;
    private int currentPort = 0; // the value is irrelevant if currentHost is null

    private void log(String m) {
        if (BuildConfig.DEBUG)
            Log.d("Yoke", m);
    }

    private void logError(String m, Exception e) {
        Log.e("Yoke", m);
        if (e != null) {
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement element : e.getStackTrace()) {
                sb.append(element.toString());
                sb.append("\n");
            }
            Log.e("Yoke", sb.toString());
        }
        YokeActivity.this.runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(YokeActivity.this);
            builder.setMessage(m);
            builder.setNegativeButton(R.string.dismiss_error, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.cancel();
                }
            });
            builder.show();
        });
    }

    private void logInfo(String m) {
        Log.i("Yoke", m);
        YokeActivity.this.runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(YokeActivity.this);
            builder.setMessage(m);
            builder.setNegativeButton(R.string.dismiss_error, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.cancel();
                }
            });
            builder.show();
        });
    }

    private void deleteRecursively(File joypadPath) throws IOException {
        // Deleting a folder without emptying it first fails or raises an error,
        // and there is no one-liner in this version to empty its contents. So:
        ArrayList<File> erasables = new ArrayList<>();
        erasables.add(joypadPath);
        for (int i = 0; i < erasables.size(); i++) {
            // don't optimize the line above. The ArrayList will be modified at every iteration.
            File folder = erasables.get(i);
            if (folder.isDirectory()) {
                for (String entry : folder.list()) {
                    erasables.add(new File(folder, entry));
                }
            }
        }
        // Files should already be ordered by depth, so we iterate backwards:
        for (int i = erasables.size() - 1; i >= 0; i--) {
            File currentFile = erasables.get(i);
            if (!currentFile.delete()) {
                throw new IOException(String.format(res.getString(R.string.error_could_not_delete), currentFile));
            }
        }
    }

    class WebAppInterface {
        Context mContext;

        // Instantiate the interface and set the context
        WebAppInterface(Context c) {
            mContext = c;
        }

        // The following methods are called from within the WebView.

        /* update_vals(String vals) sends an ISO-8859-1 string containing the
         * current gamepad status, which is sent to the PC client as a stream of
         * bytes.
         *
         * ArrayBuffers can be sent by encoding them as an ISO-8859-1 string first.
         * There doesn't seem to be a way to send an arbitrary number of
         * bytes directly.
        */
        @JavascriptInterface
        public void update_vals(String vals) {
            vals_buffer = vals.getBytes(Charset.forName("ISO-8859-1"));
        }

        /* Set the disconnection pattern.
         * If set, Yoke will send three times this pattern in the event of deliberate
         * disconnection (when the user taps the Reconnect button or selects a different
         * option from the spinner).
         *
         * This pattern is unset automatically every time a new page loads.
         */
        @JavascriptInterface
        public void set_bye(String m) {
            disconnect_pattern = m.getBytes(Charset.forName("ISO-8859-1"));
        }

        /* alert() displays a pop-up on the Yoke app. This is intended as a replacement
         * of the JavaScript alert() function, as prompts can't be shown on a WebView.
         *
         * Unlike JS's native function, this function doesn't block the JavaScript thread.
         */
        @JavascriptInterface
        public void alert(String m) {
            AlertDialog.Builder builder = new AlertDialog.Builder(YokeActivity.this, R.style.WebviewPrompt);
            builder.setTitle(R.string.alert_from_webview);
            builder.setMessage(m);
            builder.setNegativeButton(R.string.dismiss_alert, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.cancel();
                }
            });
            builder.show();
        }
    }

    // https://stackoverflow.com/questions/15758856/android-how-to-download-file-from-webserver/
    class DownloadFilesFromURL extends AsyncTask<String, Long, Long> {
        // This class can be used in two ways:
        // with one argument, parses the manifest and downloads the content from the entire webserver.
        // with two arguments, downloads a specific file. For the moment we use this to download the manifest
        // as a crude ping and a way to ensure the IP is actually running Yoke. 

        private final long INDETERMINATE = -1L;
        private final long DETERMINATE = -2L;
        private final long UPDATE_SUCCESS = -8L;
        private final long PING_SUCCESS = -16L;

        private final int MAX_PROGRESS = 1000;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            try {
                if (futureJoypadPath.exists()) {
                    deleteRecursively(futureJoypadPath);
                }
                log(String.format(
                    res.getString(R.string.log_creating_folder), futureJoypadPath.getAbsolutePath()
                ));
                if (!futureJoypadPath.mkdirs()) {
                    throw new IOException(String.format(
                        res.getString(R.string.error_could_not_create_folder), futureJoypadPath.getAbsolutePath()
                    ));
                }
            } catch (SecurityException e) {
                logError(res.getString(R.string.error_security_exception), e);
                cancel(true);
            } catch (IOException e) {
                logError(e.getMessage(), e);
                cancel(true);
            }
        }

        @Override
        protected Long doInBackground(String... f_url) {
            StringBuilder manifestSB = new StringBuilder();
            byte data[] = new byte[1024];
            try {
                if (f_url.length == 1) {
                    // Download the whole server
                    URL url = new URL(f_url[0] + "manifest.json");
                    InputStream input = new BufferedInputStream(url.openStream(), 8192);
                    OutputStream output = new FileOutputStream(futureManifestPath);
                    int count;
                    publishProgress(0L, INDETERMINATE);
                    while ((count = input.read(data)) != -1) {
                        output.write(data, 0, count);
                    }
                    BufferedReader inputBR = new BufferedReader(
                        new FileReader(futureManifestPath)
                    );
                    String line = "";
                    while ((line = inputBR.readLine()) != null) {
                        manifestSB.append(line).append("\n");
                    }
                    JSONObject manifestJSON = new JSONObject(manifestSB.toString());

                    long totalBytes = manifestJSON.optLong("size", INDETERMINATE);
                    if (totalBytes != INDETERMINATE)
                        publishProgress(0L, DETERMINATE);
                    JSONArray entries = manifestJSON.getJSONArray("folders");
                    for (int i = 0, length = entries.length(); i < length; i++) {
                        File newFolder = new File(futureJoypadPath, entries.getString(i));
                        log(String.format(res.getString(R.string.log_creating_folder), newFolder.getAbsolutePath()));
                        if (!newFolder.mkdirs()) {
                            throw new IOException(String.format(
                                res.getString(R.string.error_could_not_create_folder), newFolder.getAbsolutePath()
                            ));
                        }
                    }

                    entries = manifestJSON.getJSONArray("files");
                    long cumulativeBytes = 0;
                    for (int i = 0, length=entries.length(); i < length; i++) {
                        File newFile = new File(futureJoypadPath, entries.getString(i));
                        log(String.format(res.getString(R.string.log_downloading_file), newFile.getAbsolutePath()));
                        input = new BufferedInputStream(new URL(f_url[0] + entries.getString(i)).openStream(), 8192);
                        output = new FileOutputStream(newFile);

                        while ((count = input.read(data)) != -1) {
                            cumulativeBytes += count;
                            output.write(data, 0, count);
                            publishProgress(cumulativeBytes, totalBytes);
                        }

                        output.flush();
                        output.close();
                        input.close();
                    }
                    return UPDATE_SUCCESS;
                } else {
                    // download just one file
                    URL url = new URL(f_url[0] + "/" + f_url[1]);
                    InputStream input = new BufferedInputStream(url.openStream(), 8192);
                    publishProgress(0L, INDETERMINATE);
                    while (input.read(data) != -1) { }
                    return PING_SUCCESS;
                }
            } catch (IOException e) {
                if (e.getMessage().contains(" ECONNREFUSED ")) {
                    logError(res.getString(R.string.error_connection_refused), e);
                } else if (e.getMessage().contains(" ENETUNREACH ")) {
                    logError(res.getString(R.string.error_network_unreachable), e);
                } else if (e.getMessage().startsWith("Failed to connect to")) {
                    logError(res.getString(R.string.error_failed_download), e);
                } else {
                    logError(e.getMessage(), e);
                }
                cancel(true);
            } catch (JSONException e) {
                logError(String.format(res.getString(R.string.error_json_exception), e.getLocalizedMessage()), e);
                cancel(true);
            } catch (SecurityException e) {
                logError(res.getString(R.string.error_security_exception), e);
                cancel(true);
            }
            return 0L;
        }

        protected void onProgressUpdate(Long... progress) {
            if (progress[1] == INDETERMINATE) {
                mProgressBar.setIndeterminate(true);
                mProgressBar.setVisibility(View.VISIBLE);
            } else if (progress[1] == DETERMINATE) {
                mTextView.setText(res.getString(R.string.toolbar_connected_to));
                mProgressBar.setIndeterminate(false);
                mProgressBar.setProgress(0);
                mProgressBar.setMax((int)MAX_PROGRESS);
                mProgressBar.setVisibility(View.VISIBLE);
            } else {
                mProgressBar.setProgress((int)(progress[0]*MAX_PROGRESS/progress[1]));
            }
        }

        @Override
        protected void onPostExecute(Long result) {
            if (result == UPDATE_SUCCESS) {
                mProgressBar.setVisibility(View.INVISIBLE);
                try {
                    if (currentJoypadPath.exists()) {
                        deleteRecursively(currentJoypadPath);
                    }
                    if (!futureJoypadPath.renameTo(currentJoypadPath)) {
                        logError(String.format(res.getString(R.string.error_could_not_rename), futureJoypadPath.getAbsolutePath()), null);
                        cancel(true);
                    }
                    Toast.makeText(YokeActivity.this, res.getString(R.string.toast_layout_succesfully_upgraded), Toast.LENGTH_LONG).show();
                } catch (IOException e) {
                    logError(String.format(res.getString(R.string.error_io_exception), e.getLocalizedMessage()), e);
                    cancel(true);
                }
            } else if (result == PING_SUCCESS) {
                mProgressBar.setVisibility(View.INVISIBLE);
                mTextView.setText(res.getString(R.string.toolbar_connected_to));
                if (currentMainPath.exists()) {
                    String url = "file://" + currentMainPath.toString();
                    log(String.format(res.getString(R.string.log_loading_url), url));
                    wv.loadUrl(url);
                } else {
                    logInfo(String.format(
                        res.getString(R.string.info_download_layout_first),
                        res.getString(R.string.menu_upgrade_layout),
                        res.getString(R.string.toolbar_reconnect)
                    ));
                }
            }
        }

        @Override
        protected void onCancelled() {
            mProgressBar.setVisibility(View.INVISIBLE);
            mTextView.setText(res.getString(R.string.toolbar_connect_to));
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPref = getPreferences(Context.MODE_PRIVATE);
        setContentView(R.layout.main_wv);

        wv = findViewById(R.id.webView);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.addJavascriptInterface(new WebAppInterface(this), "Yoke");

        mNsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        // Many devices drop incoming multicast on Wi-Fi unless an app holds this lock.
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            mMulticastLock = wifi.createMulticastLock("yoke-discovery");
            mMulticastLock.setReferenceCounted(false);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Localization. TODO: detect NOTHING and MANUAL CONNECTION buttons by id, not by content.
        res = getResources();
        NOTHING = res.getString(R.string.dropdown_nothing);
        ENTER_IP = res.getString(R.string.dropdown_enter_ip);
        oldtgt = NOTHING;

        // Paths for layout (can't define them until Android context is established)
        currentJoypadPath = new File(getFilesDir(), "joypad");
        currentMainPath = new File(currentJoypadPath, "main.html");
        currentManifestPath = new File(currentJoypadPath, "manifest.json");
        futureJoypadPath = new File(getFilesDir(), "future");
        futureMainPath = new File(futureJoypadPath, "main.html");
        futureManifestPath = new File(futureJoypadPath, "manifest.json");

        // Progress bar for download:
        mProgressBar = (ProgressBar) findViewById(R.id.downloadProgress);

        // Filling spinner with addresses to connect to:
        mTextView = (TextView) findViewById(R.id.textView);

        mSpinner = (Spinner) findViewById(R.id.spinner);
        mAdapter = new ArrayAdapter<>(getApplicationContext(), android.R.layout.simple_spinner_item);
        mAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mAdapter.add(NOTHING);
        mAdapter.add(ENTER_IP);

        for (String adr : sharedPref.getString("addresses", "").split(System.lineSeparator())) {
            adr = adr.trim(); // workaround for android bug where random whitespace is added to Strings in shared preferences
            if (!adr.isEmpty()) {
                mAdapter.add(adr);
                mServiceNames.add(adr);
                log(String.format(res.getString(R.string.log_adding_address), adr));
            }
        }
        mSpinner.setAdapter(mAdapter);
        mSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long l) {
                String tgt = parent.getItemAtPosition(pos).toString();

                if (mSpinnerAutomatic) {
                    mSpinnerAutomatic = false;
                    log(String.format(res.getString(R.string.log_spinner_automatic), tgt, pos));
                    return;
                }

                log(String.format(res.getString(R.string.log_spinner_selected), tgt, pos));

                // clean up old target if no longer available
                if (!mServiceNames.contains(oldtgt) && !oldtgt.equals(NOTHING) && !oldtgt.equals(ENTER_IP)) {
                    mAdapter.remove(oldtgt);
                    if (oldtgt.equals(tgt)) tgt = NOTHING;
                }

                if (tgt.equals(NOTHING)) {
                    requestDisconnect();
                    closeSocket();
                } else if (tgt.equals(ENTER_IP)) {
                    mSpinnerAutomatic = true;
                    mSpinner.setSelection(mAdapter.getPosition(oldtgt));

                    AlertDialog.Builder builder = new AlertDialog.Builder(YokeActivity.this);
                    builder.setTitle(res.getString(R.string.enter_ip_title));

                    final EditText input = new EditText(YokeActivity.this);
                    input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
                    input.setHint(res.getString(R.string.enter_ip_hint));

                    builder.setView(input);

                    builder.setPositiveButton(res.getString(R.string.enter_ip_ok), (dialog, which) -> {
                        String name = input.getText().toString();

                        boolean invalid = name.split(":").length != 2;

                        if (!invalid) {
                            try {
                                Integer.parseInt(name.split(":")[1]);
                            } catch (NumberFormatException e) {
                                invalid = true;
                            }
                        }

                        if (invalid) {
                            logInfo(res.getString(R.string.info_invalid_address));
                            dialog.cancel();
                        } else {
                            mServiceNames.add(name);
                            mAdapter.add(name);

                            SharedPreferences.Editor editor = sharedPref.edit();
                            String addresses = sharedPref.getString("addresses", "");
                            addresses = addresses + name + System.lineSeparator();
                            editor.putString("addresses", addresses);
                            editor.apply();

                            oldtgt = name;
                            mSpinnerAutomatic = true;
                            mSpinner.setSelection(mAdapter.getPosition(name));
                            connectToAddress(name);
                        }
                    });
                    builder.setNegativeButton(res.getString(R.string.enter_ip_cancel), (dialog, which) -> {
                        dialog.cancel();
                    });
                    builder.show();
                } else {
                    log(String.format(res.getString(R.string.log_service_targeting), tgt));

                    connectToTarget(tgt);
                }
                if (!tgt.equals(ENTER_IP))
                    oldtgt = mSpinner.getSelectedItem().toString();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                log(res.getString(R.string.log_nothing_selected));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mMulticastLock != null) mMulticastLock.acquire();
        try {
            mNsdActive = true;
            mNsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this);
        } catch (IllegalArgumentException e) {
            mNsdActive = false;
            log("NSD discovery not started: " + e.getMessage());
        }
        startBroadcastDiscovery();

        handler = new Handler();
        handler.post(new Runnable() {

            @Override
            public void run() {
                update();

                if (handler != null)
                    handler.postDelayed(this, poll_rate);
            }
        });

    }

    @Override
    protected void onPause() {
        super.onPause();

        stopNsdDiscovery();
        stopBroadcastDiscovery();
        if (mMulticastLock != null && mMulticastLock.isHeld()) mMulticastLock.release();

        closeSocket();

        handler = null;
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void update() {
        if (mSocket != null && vals_buffer != null) {
            send(vals_buffer);
        }
    }

    public void send(byte[] msg) {
        try {
            mSocket.send(new DatagramPacket(msg, msg.length));
        } catch (SecurityException e) {
            logError(res.getString(R.string.error_sending_security_exception), e);
            closeSocket();
        } catch (IOException e) {
            if (e.getMessage().contains(" ECONNREFUSED ")) {
                logError(res.getString(R.string.error_pc_client_closed), e);
            } else if (e.getMessage().contains(" ENETUNREACH ")) {
                logError(res.getString(R.string.error_network_unreachable), e);
            } else {
                logError(e.getMessage(), e);
            }
            closeSocket();
        }
    }

    public void connectToService(String tgt) {
        NsdServiceInfo service = mServiceMap.get(tgt);
        log(String.format(res.getString(R.string.log_service_resolving), service.getServiceType()));
        mNsdManager.resolveService(service, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                logError(String.format(res.getString(R.string.log_service_resolve_error), errorCode), null);
                closeSocket();
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                // check name again (could have changed in the mean time)
                if (tgt.equals(serviceInfo.getServiceName())) {
                    log(String.format(res.getString(R.string.log_service_resolve_success), serviceInfo));

                    mService = serviceInfo;
                    openSocket(mService.getHost().getHostAddress(), mService.getPort());

                }
            }
        });
    }

    // Connect to a spinner entry: a broadcast-discovered server, an mDNS service or a typed "ip:port".
    public void connectToTarget(String tgt) {
        String adr = mBroadcastMap.get(tgt);
        if (adr != null) {
            connectToAddress(adr);
        } else if (mServiceMap.containsKey(tgt)) {
            connectToService(tgt);
        } else {
            connectToAddress(tgt);
        }
    }

    public void connectToAddress(String tgt) {
        log(String.format(res.getString(R.string.log_directly_connecting), tgt));
        String[] addr = tgt.split(":");
        if (addr.length != 2 || !addr[1].matches("\\d+")) {
            // A discovered server that has since disappeared, rather than a typed "ip:port".
            logInfo(res.getString(R.string.info_connected_to_nowhere));
            return;
        }
        (new Thread(()-> openSocket(addr[0], Integer.parseInt(addr[1])))).start();
    }

    public void reconnect(View view) {
        String tgt = mSpinner.getSelectedItem().toString();
        if (currentHost == null) {
            logInfo(res.getString(R.string.info_connected_to_nowhere));
        } else {
            requestDisconnect();
            closeSocket();
            mSpinnerAutomatic = true;
            mSpinner.setSelection(mAdapter.getPosition(tgt));
            connectToTarget(tgt);
            mSpinnerAutomatic = false;
        }
    }

    public void showOverflowMenu(View view) {
        PopupMenu popup = new PopupMenu(this, view);
        popup.inflate(R.menu.overflow);
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.upgradeLayout) {
                    if (currentHost != null) {
                        new DownloadFilesFromURL().execute(
                            "http://" + currentHost + ":" + Integer.toString(currentPort) + "/"
                        );
                    } else {
                        logInfo(res.getString(R.string.info_connected_to_nowhere));
                    }
                    return true;
                } else {
                    return false;
                }
            }
        });
        popup.show();
    }


    public void openSocket(String host, int port) {
        currentHost = host;
        currentPort = port;
        log(String.format(res.getString(R.string.log_opening_udp), host, port));

        try {
            mSocket = new DatagramSocket(0);
            mSocket.connect(InetAddress.getByName(host), port);

            log(res.getString(R.string.log_open_udp_success));
            new DownloadFilesFromURL().execute(
                "http://" + currentHost + ":" + Integer.toString(currentPort) + "/", "manifest.json"
            );

        } catch (SocketException | UnknownHostException e) {
            mSocket = null; currentHost = null;
            YokeActivity.this.runOnUiThread(() -> {
                mTextView.setText(res.getString(R.string.toolbar_connect_to));
                mSpinnerAutomatic = true;
                mSpinner.setSelection(mAdapter.getPosition(NOTHING));
            });
            logError(String.format(res.getString(R.string.error_open_udp_error), host, port), e);
        }
    }

    public void onDiscoveryStarted(String serviceType) {
        log(String.format(res.getString(R.string.log_discovery_started), serviceType));
    }

    @Override
    public void onServiceFound(NsdServiceInfo service) {
        log(res.getString(R.string.log_service_found) + service);
        mServiceMap.put(service.getServiceName(), service);
        addTarget(service.getServiceName());
    }



    @Override
    public void onServiceLost(NsdServiceInfo service) {
        log(res.getString(R.string.log_service_lost) + service);
        mServiceMap.remove(service.getServiceName());
        removeTargetIfGone(service.getServiceName());
    }

    private void addTarget(String name) {
        if (!mServiceNames.contains(name)) mServiceNames.add(name);
        this.runOnUiThread(() -> {
            if (mAdapter.getPosition(name) < 0) mAdapter.add(name);
        });
    }

    // Only drop a discovered server once no discovery method still sees it.
    private void removeTargetIfGone(String name) {
        if (mServiceMap.containsKey(name) || mBroadcastMap.containsKey(name)) return;
        mServiceNames.remove(name);
        this.runOnUiThread(() -> {
            if (mSpinner.getSelectedItem().toString().equals(name))
                return;
            mAdapter.remove(name);
        });
    }

    private void stopNsdDiscovery() {
        if (!mNsdActive) return;
        mNsdActive = false;
        try {
            mNsdManager.stopServiceDiscovery(this);
        } catch (IllegalArgumentException e) {
            log("NSD discovery was not running: " + e.getMessage());
        }
    }

    private void startBroadcastDiscovery() {
        Thread t = new Thread(this::broadcastDiscoveryLoop, "yoke-discovery");
        mBroadcastThread = t;
        t.start();
    }

    private void stopBroadcastDiscovery() {
        Thread t = mBroadcastThread;
        mBroadcastThread = null;
        if (t != null) t.interrupt();
    }

    private Set<InetAddress> broadcastAddresses() {
        Set<InetAddress> out = new HashSet<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
            // Per-interface broadcasts also cover USB/Bluetooth tethering and hotspot interfaces.
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
        } catch (IOException e) {
            log("Listing interfaces failed: " + e.getMessage());
        }
        return out;
    }

    private void broadcastDiscoveryLoop() {
        Thread self = Thread.currentThread();
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setBroadcast(true);
            sock.setSoTimeout(250);
            byte[] buf = new byte[512];
            long nextSend = 0;
            while (mBroadcastThread == self) {
                long now = System.currentTimeMillis();
                if (now >= nextSend) {
                    for (InetAddress adr : broadcastAddresses()) {
                        try {
                            sock.send(new DatagramPacket(DISCOVERY_REQUEST, DISCOVERY_REQUEST.length, adr, DISCOVERY_PORT));
                        } catch (IOException e) {
                            // Some interfaces refuse broadcasts; the others may still work.
                        }
                    }
                    nextSend = now + DISCOVERY_INTERVAL_MS;
                    expireBroadcastTargets(now);
                }
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(reply);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                handleDiscoveryReply(reply);
            }
        } catch (IOException e) {
            log("Broadcast discovery stopped: " + e.getMessage());
        }
    }

    // Reply format: "YOKE <port> <service name>"
    private void handleDiscoveryReply(DatagramPacket reply) {
        String msg = new String(reply.getData(), 0, reply.getLength(), Charset.forName("UTF-8"));
        String[] parts = msg.split(" ", 3);
        if (parts.length != 3 || !parts[0].equals("YOKE")) return;
        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return;
        }
        String name = parts[2].trim();
        if (name.isEmpty()) return;
        // Use the packet's source address: it is the server's IP on the interface that reached us.
        String adr = reply.getAddress().getHostAddress() + ":" + port;
        mBroadcastSeen.put(name, System.currentTimeMillis());
        if (!adr.equals(mBroadcastMap.put(name, adr))) {
            log("Broadcast discovery found " + name + " at " + adr);
            addTarget(name);
        }
    }

    private void expireBroadcastTargets(long now) {
        for (Map.Entry<String, Long> e : mBroadcastSeen.entrySet()) {
            if (now - e.getValue() > DISCOVERY_STALE_MS) {
                mBroadcastSeen.remove(e.getKey());
                mBroadcastMap.remove(e.getKey());
                removeTargetIfGone(e.getKey());
            }
        }
    }

    @Override
    public void onDiscoveryStopped(String serviceType) {
        log(String.format(res.getString(R.string.log_discovery_stopped), serviceType));
    }

    @Override
    public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        // The listener was never registered, so stopServiceDiscovery() would throw here.
        // Broadcast discovery keeps running, so only log instead of showing an error dialog.
        mNsdActive = false;
        log(String.format(res.getString(R.string.error_discovery_failed), errorCode));
    }

    @Override
    public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        log(String.format(res.getString(R.string.error_discovery_failed), errorCode));
    }

    // requestDisconnect() sends the disconnect_pattern set by set_disconnect_pattern()
    // before closing the connection. This signals the PC client to disconnect the device.
    //
    // This warning is not to be sent on accidental disconnections (like exiting the app)
    // or error states (that usually happen when the client is no longer listening).
    private void requestDisconnect() {
        if (mSocket != null && disconnect_pattern != null) {
            // Send three times, just in case:
            send(disconnect_pattern);
            send(disconnect_pattern);
            send(disconnect_pattern);
            log(res.getString(R.string.log_warning_shot));
        }
    }

    // closeSocket() closes the connection as soon as possible. It disables the periodic
    // status transmissions, then closes the socket, then updates the UI to reflect this.
    private void closeSocket() {
        log(res.getString(R.string.log_closing_connection));
        // Don't update the last status report:
        wv.loadUrl("about:blank");
        vals_buffer = null;
        disconnect_pattern = null;
        mService = null;
        currentHost = null;
        mTextView.setText(res.getString(R.string.toolbar_connect_to));
        if (!mSpinner.getSelectedItem().toString().equals(NOTHING)) {
            mSpinnerAutomatic = true;
            mSpinner.setSelection(mAdapter.getPosition(NOTHING));
        }
        // Delayed to the very end, to sidestep occasional errors:
        if (mSocket != null) {
            mSocket.close();
            mSocket = null;
            log(res.getString(R.string.log_udp_closed));
        }
    }
}

