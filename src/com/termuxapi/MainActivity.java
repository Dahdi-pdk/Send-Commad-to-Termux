package com.termuxapi;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final int REQ_TERMUX = 1001;
    private static final String PERM_TERMUX = "com.termux.permission.RUN_COMMAND";

    private EditText etServerUrl;
    private EditText etCommand;
    private TextView tvOutput;
    private ScrollView scrollOutput;
    private RadioButton rbHttp;
    private String pendingCommand = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_main);

        etServerUrl  = (EditText)  findViewById(R.id.et_server_url);
        etCommand    = (EditText)  findViewById(R.id.et_command);
        tvOutput     = (TextView)  findViewById(R.id.tv_output);
        scrollOutput = (ScrollView) findViewById(R.id.scroll_output);
        rbHttp       = (RadioButton) findViewById(R.id.rb_http);

        RadioGroup  rgMode    = (RadioGroup)  findViewById(R.id.rg_mode);
        RadioButton rbIntent  = (RadioButton) findViewById(R.id.rb_intent);
        Button btnRun         = (Button) findViewById(R.id.btn_run);
        Button btnClear       = (Button) findViewById(R.id.btn_clear);
        Button btnCopy        = (Button) findViewById(R.id.btn_copy);

        rbIntent.setChecked(true);

        rgMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                etServerUrl.setVisibility(rbHttp.isChecked() ? View.VISIBLE : View.GONE);
            }
        });

        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jalankanPerintah();
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tvOutput.setText("");
            }
        });

        // Tombol salin — salin seluruh isi output
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String isi = tvOutput.getText().toString().trim();
                if (isi.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Output kosong", Toast.LENGTH_SHORT).show();
                } else {
                    salinKeClipboard(isi);
                }
            }
        });

        // Long-press pada output juga bisa menyalin
        tvOutput.setLongClickable(true);
        tvOutput.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                String isi = tvOutput.getText().toString().trim();
                if (!isi.isEmpty()) salinKeClipboard(isi);
                return true;
            }
        });
    }

    // ── Logika utama ───────────────────────────────────────────────────────────

    private void jalankanPerintah() {
        String perintah = etCommand.getText().toString().trim();
        if (perintah.isEmpty()) {
            tambahOutput("[Error] Perintah tidak boleh kosong.");
            return;
        }
        if (rbHttp.isChecked()) {
            kirimViaHttp(perintah);
        } else {
            kirimViaIntent(perintah);
        }
    }

    // ── Mode Intent ────────────────────────────────────────────────────────────

    private void kirimViaIntent(String perintah) {
        // Dangerous permission harus diminta runtime di API 23+
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(PERM_TERMUX) != PackageManager.PERMISSION_GRANTED) {
                pendingCommand = perintah;
                requestPermissions(new String[]{PERM_TERMUX}, REQ_TERMUX);
                tambahOutput("[Info] Meminta izin RUN_COMMAND...\n"
                    + "Jika tidak ada dialog:\n"
                    + "Buka Termux → Settings → Allow External Apps → ON");
                return;
            }
        }
        kirimIntentSekarang(perintah);
    }

    private void kirimIntentSekarang(String perintah) {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.termux", "com.termux.app.RunCommandService");
            intent.setAction("com.termux.RUN_COMMAND");
            intent.putExtra("com.termux.RUN_COMMAND_PATH",
                "/data/data/com.termux/files/usr/bin/bash");
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
                new String[]{"-c", perintah});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR",
                "/data/data/com.termux/files/home");
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            startService(intent);
            tambahOutput("[Intent] Perintah dikirim:\n$ " + perintah);
        } catch (Exception e) {
            tambahOutput("[Error Intent] " + e.getMessage()
                + "\n\nPastikan:\n"
                + "1. Termux sudah diinstal\n"
                + "2. Settings Termux → Allow External Apps → ON");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        if (requestCode == REQ_TERMUX) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingCommand != null) {
                    kirimIntentSekarang(pendingCommand);
                    pendingCommand = null;
                }
            } else {
                tambahOutput("[Error] Izin RUN_COMMAND ditolak sistem.\n\n"
                    + "Cara mengaktifkan manual:\n"
                    + "1. Buka Termux\n"
                    + "2. Ketik: am startservice --user 0 -n com.termux/.app.RunCommandService\n"
                    + "   (untuk uji coba)\n"
                    + "3. Atau: Settings → Allow External Apps → ON");
            }
        }
    }

    // ── Mode HTTP ──────────────────────────────────────────────────────────────

    private void kirimViaHttp(final String perintah) {
        final String urlServer = etServerUrl.getText().toString().trim();
        if (urlServer.isEmpty()) {
            tambahOutput("[Error] URL server kosong.");
            return;
        }
        tambahOutput("[HTTP] Mengirim ke " + urlServer + "\n$ " + perintah);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(urlServer);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");

                    OutputStream os = conn.getOutputStream();
                    os.write(perintah.getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    final int kode = conn.getResponseCode();
                    BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String baris;
                    while ((baris = br.readLine()) != null) {
                        sb.append(baris).append("\n");
                    }
                    br.close();
                    conn.disconnect();

                    final String hasil = sb.toString().trim();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tambahOutput("[HTTP " + kode + "] Output:\n" + hasil);
                        }
                    });

                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tambahOutput("[Error HTTP] " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    // ── Utilitas ───────────────────────────────────────────────────────────────

    private void salinKeClipboard(String teks) {
        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Termux Output", teks));
        Toast.makeText(this, "Output disalin ke clipboard", Toast.LENGTH_SHORT).show();
    }

    private void tambahOutput(String teks) {
        tvOutput.append(teks + "\n\n");
        scrollOutput.post(new Runnable() {
            @Override
            public void run() {
                scrollOutput.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }
}