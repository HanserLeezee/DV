package com.hans.dv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 连接页: 自动搜索局域网里的 DV 服务, 或手动输入 IP。 */
public class MainActivity extends Activity {

    static final int TCP_PORT = 27183;
    static final int DISCOVERY_PORT = 27184;

    private EditText ipInput;
    private LinearLayout serverList;
    private TextView searchStatus;
    private Button searchBtn;
    private volatile boolean searching;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(18, 22, 30));
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText("Deliver Vision");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("先在电脑上运行 dv_server, 再搜索或输入电脑 IP");
        sub.setTextColor(Color.rgb(150, 160, 175));
        sub.setPadding(0, dp(4), 0, dp(24));
        root.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        ipInput = new EditText(this);
        ipInput.setHint("电脑 IP, 例如 192.168.1.10");
        ipInput.setHintTextColor(Color.rgb(100, 110, 125));
        ipInput.setTextColor(Color.WHITE);
        ipInput.setSingleLine(true);
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ipInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        ipInput.setText(prefs().getString("last_ip", ""));
        ipInput.setOnEditorActionListener((v, id, e) -> {
            connect(ipInput.getText().toString().trim());
            return true;
        });
        row.addView(ipInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button go = new Button(this);
        go.setText("连接");
        go.setOnClickListener(v -> connect(ipInput.getText().toString().trim()));
        row.addView(go);
        root.addView(row);

        searchBtn = new Button(this);
        searchBtn.setText("搜索局域网");
        searchBtn.setOnClickListener(v -> startSearch());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        root.addView(searchBtn, lp);

        searchStatus = new TextView(this);
        searchStatus.setTextColor(Color.rgb(150, 160, 175));
        searchStatus.setPadding(0, dp(12), 0, dp(8));
        root.addView(searchStatus);

        ScrollView scroll = new ScrollView(this);
        serverList = new LinearLayout(this);
        serverList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(serverList);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        TextView hint = new TextView(this);
        hint.setText("有线模式: 电脑执行 adb reverse tcp:27183 tcp:27183, 然后这里填 127.0.0.1");
        hint.setTextColor(Color.rgb(100, 110, 125));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        root.addView(hint);

        setContentView(root);
        startSearch();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("dv", MODE_PRIVATE);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void connect(String host) {
        if (host.isEmpty()) {
            searchStatus.setText("请输入电脑 IP");
            return;
        }
        int port = TCP_PORT;
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(host.substring(colon + 1));
                host = host.substring(0, colon);
            } catch (NumberFormatException ignored) {
            }
        }
        prefs().edit().putString("last_ip", colon > 0 ? host + ":" + port : host).apply();
        Intent i = new Intent(this, StreamActivity.class);
        i.putExtra("host", host);
        i.putExtra("port", port);
        startActivity(i);
    }

    /** 向 255.255.255.255 和本网段广播地址发 "DV?", 收集 "DV!{json}" 回复。 */
    private void startSearch() {
        if (searching) return;
        searching = true;
        searchBtn.setEnabled(false);
        searchStatus.setText("正在搜索...");
        serverList.removeAllViews();
        new Thread(() -> {
            Map<String, String> found = new LinkedHashMap<>();
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket();
                sock.setBroadcast(true);
                sock.setSoTimeout(250);
                byte[] q = "DV?".getBytes(StandardCharsets.US_ASCII);
                InetAddress[] targets = broadcastTargets();
                byte[] buf = new byte[512];
                long end = System.currentTimeMillis() + 2000;
                long nextSend = 0;
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() >= nextSend) {
                        for (InetAddress t : targets) {
                            try {
                                sock.send(new DatagramPacket(q, q.length, t, DISCOVERY_PORT));
                            } catch (Exception ignored) {
                            }
                        }
                        nextSend = System.currentTimeMillis() + 600;
                    }
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        sock.receive(p);
                        String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        if (!s.startsWith("DV!")) continue;
                        JSONObject o = new JSONObject(s.substring(3));
                        String addr = p.getAddress().getHostAddress();
                        int port = o.optInt("port", TCP_PORT);
                        String key = port == TCP_PORT ? addr : addr + ":" + port;
                        if (!found.containsKey(key)) {
                            found.put(key, o.optString("name", "PC"));
                            final String k = key, name = found.get(key);
                            runOnUiThread(() -> addServer(name, k));
                        }
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> searchStatus.setText("搜索出错: " + e.getMessage()));
            } finally {
                if (sock != null) sock.close();
                final int n = found.size();
                runOnUiThread(() -> {
                    searching = false;
                    searchBtn.setEnabled(true);
                    searchStatus.setText(n == 0
                            ? "没找到。确认电脑端已运行、在同一 Wi-Fi, 并在 Windows 防火墙里允许 Python"
                            : "找到 " + n + " 台电脑, 点击连接");
                });
            }
        }, "dv-discovery").start();
    }

    private InetAddress[] broadcastTargets() {
        try {
            InetAddress all = InetAddress.getByName("255.255.255.255");
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                @SuppressWarnings("deprecation")
                android.net.DhcpInfo d = wm.getDhcpInfo();
                if (d != null && d.ipAddress != 0) {
                    int mask = d.netmask != 0 ? d.netmask : 0x00FFFFFF; // little-endian
                    int bc = (d.ipAddress & mask) | ~mask;
                    byte[] b = {(byte) bc, (byte) (bc >> 8), (byte) (bc >> 16), (byte) (bc >> 24)};
                    return new InetAddress[]{all, InetAddress.getByAddress(b)};
                }
            }
            return new InetAddress[]{all};
        } catch (Exception e) {
            return new InetAddress[0];
        }
    }

    private void addServer(String name, String addr) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(name + "    " + addr);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(v -> {
            ipInput.setText(addr);
            connect(addr);
        });
        serverList.addView(b);
        b.setVisibility(View.VISIBLE);
    }
}
