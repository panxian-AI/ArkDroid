package ai.panxian.arkdroid;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 内置浏览器：直接开 AstrBot 面板，不用切到别的 App。 */
public class PanelActivity extends Activity {

    static final String PANEL_URL = "http://127.0.0.1:6185";
    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";

    private WebView web;
    private TextView status;
    private String target = PANEL_URL;
    private String title = "AstrBot 面板";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String u = getIntent().getStringExtra(EXTRA_URL);
        if (u != null && !u.isEmpty()) {
            target = u;
        }
        String t = getIntent().getStringExtra(EXTRA_TITLE);
        if (t != null && !t.isEmpty()) {
            title = t;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F141A"));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(16, 12, 16, 12);

        status = new TextView(this);
        status.setText(title + " · " + target);
        status.setTextColor(Color.parseColor("#B7C3D0"));
        status.setTextSize(13);
        status.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(status);

        bar.addView(button("刷新", v -> web.reload()));
        bar.addView(button("账号", v -> showCreds()));
        bar.addView(button("外部", v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
            } catch (Exception ignored) {
                // 没浏览器就算了，内置的还能用
            }
        }));
        root.addView(bar);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " ArkDroid/0.3");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.setBackgroundColor(Color.parseColor("#0F141A"));
        web.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(web);

        setContentView(root);
        web.loadUrl(target);
    }

    private Button button(String text, android.view.View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private void showCreds() {
        String text = Creds.summary();
        status.setText(text);
        status.postDelayed(() -> status.setText(title + " · " + target), 6000);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
