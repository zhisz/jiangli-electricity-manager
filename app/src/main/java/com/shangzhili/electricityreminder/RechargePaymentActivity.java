package com.shangzhili.electricityreminder;

import android.annotation.SuppressLint;
import android.app.Activity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;

/**
 * 充值专用的受限支付过渡页。
 *
 * <p>现有校付宝接口返回的是 H5 收银台地址，并没有微信原生 App 支付要求的 AppID、
 * prepayId 和商户签名，因此不能直接调用微信 OpenSDK。本页面只加载校付宝/微信支付
 * 白名单域名；当官方 H5 页面生成 {@code weixin://wap/pay} 指令时，再明确交给微信 App。
 * 这样既避开部分系统浏览器阻止外部应用跳转的问题，也不需要伪造任何支付参数。</p>
 */
public final class RechargePaymentActivity extends Activity {
    private static final String EXTRA_CHECKOUT_URL = "checkoutUrl";
    private static final String EXTRA_ATTEMPT_ID = "attemptId";
    private static final String STATE_WAITING_FOR_EXTERNAL = "waitingForExternalPaymentReturn";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";

    private int appliedThemeState;
    private String checkoutUrl;
    private String attemptId;
    private WebView webView;
    private ProgressBar progress;
    private TextView statusText;
    /** 只有确实离开本页进入微信或系统浏览器后，返回时才结束过渡页。 */
    private boolean waitingForExternalPaymentReturn;
    private boolean errorDialogShowing;

    public static Intent createIntent(
            Context context, String checkoutUrl, String attemptId
    ) {
        return new Intent(context, RechargePaymentActivity.class)
                .putExtra(EXTRA_CHECKOUT_URL, checkoutUrl)
                .putExtra(EXTRA_ATTEMPT_ID, attemptId);
    }

    public static String resultAttemptId(Intent data) {
        return data == null ? null : data.getStringExtra(EXTRA_ATTEMPT_ID);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppThemeManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedThemeState = AppThemeManager.apply(this);
        super.onCreate(savedInstanceState);

        attemptId = getIntent().getStringExtra(EXTRA_ATTEMPT_ID);
        try {
            // Intent 虽然仅由本应用创建，仍在接收边界重新校验，避免以后误把 Activity 导出
            // 或其他调用路径绕过创建订单时的地址白名单。
            checkoutUrl = RechargeCheckoutUrlValidator.requireTrusted(
                    getIntent().getStringExtra(EXTRA_CHECKOUT_URL)
            );
            if (attemptId == null || attemptId.isEmpty()) {
                throw new IOException("缺少本地充值尝试标识");
            }
        } catch (IOException exception) {
            Intent result = new Intent();
            if (attemptId != null) {
                result.putExtra(EXTRA_ATTEMPT_ID, attemptId);
            }
            setResult(RESULT_CANCELED, result);
            finish();
            return;
        }
        setResult(
                RESULT_CANCELED,
                new Intent().putExtra(EXTRA_ATTEMPT_ID, attemptId)
        );
        waitingForExternalPaymentReturn = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_WAITING_FOR_EXTERNAL, false);

        setContentView(R.layout.activity_recharge_payment);
        applySystemBarInsets();
        webView = findViewById(R.id.rechargePaymentWebView);
        progress = findViewById(R.id.rechargePaymentProgress);
        statusText = findViewById(R.id.rechargePaymentStatusText);

        findViewById(R.id.rechargePaymentCloseButton).setOnClickListener(view -> finish());

        configureSecureWebView();
        // 收银台 URL 仅保存在当前 Activity 内存与 WebView 导航栈，不写入数据库、偏好或日志。
        webView.loadUrl(checkoutUrl);
    }

    /**
     * WebView 只提供 H5 支付必需能力，不开放文件、ContentProvider、定位或 JS Bridge。
     *
     * <p>JavaScript 与 DOM Storage 是校付宝收银台的正常依赖；同时禁用混合内容，
     * 可以阻止 HTTPS 收银台加载 HTTP 脚本。应用没有向页面注入任何 Java 对象，
     * 因而网页不能通过 addJavascriptInterface 访问本地业务数据。</p>
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void configureSecureWebView() {
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setUserAgentString(
                settings.getUserAgentString()
                        + " ElectricityReminder/" + BuildConfig.VERSION_NAME
        );

        // 微信 H5 中间页可能依赖跨站 Cookie。Cookie 仅保存在本应用 WebView 沙箱内，
        // 不会读取系统浏览器或微信本身的 Cookie。
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new PaymentWebViewClient());
    }

    private final class PaymentWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl().toString());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            statusText.setText(R.string.recharge_payment_loading);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!isFinishing() && !isDestroyed()) {
                statusText.setText(R.string.recharge_payment_ready);
            }
        }

        @Override
        public void onReceivedSslError(
                WebView view, SslErrorHandler handler, SslError error
        ) {
            // 支付页面绝不能像普通资讯网页一样允许用户“忽略证书错误继续访问”。
            handler.cancel();
            showPaymentError(
                    getString(R.string.recharge_payment_ssl_error), false
            );
        }

        @Override
        public void onReceivedError(
                WebView view, WebResourceRequest request, WebResourceError error
        ) {
            if (request.isForMainFrame()) {
                statusText.setText(R.string.recharge_payment_network_error);
            }
        }

        @Override
        public boolean onRenderProcessGone(
                WebView view, RenderProcessGoneDetail detail
        ) {
            // 主动接管渲染进程异常，避免 WebView 进程崩溃连带整个 App 闪退。
            if (webView != null) {
                if (webView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) webView.getParent()).removeView(webView);
                }
                webView.destroy();
                webView = null;
            }
            showPaymentError(
                    getString(R.string.recharge_payment_renderer_error), false
            );
            return true;
        }
    }

    /**
     * 白名单内 HTTPS 页面留在 WebView；标准微信支付指令显式交给微信；其余地址全部阻止。
     */
    private boolean handleNavigation(String target) {
        if (target != null && target.startsWith("weixin:")) {
            openWechatPay(target);
            return true;
        }
        if (RechargeCheckoutUrlValidator.isTrustedPaymentWebPage(target)) {
            return false;
        }
        showPaymentError(getString(R.string.recharge_payment_blocked_url), false);
        return true;
    }

    private void openWechatPay(String command) {
        try {
            String trustedCommand =
                    RechargeCheckoutUrlValidator.requireTrustedWechatPayCommand(command);
            Intent wechat = new Intent(Intent.ACTION_VIEW, Uri.parse(trustedCommand));
            // 显式指定微信，避免其他应用注册 weixin scheme 后劫持一次性支付指令。
            wechat.setPackage(WECHAT_PACKAGE);
            wechat.addCategory(Intent.CATEGORY_BROWSABLE);
            waitingForExternalPaymentReturn = true;
            startActivity(wechat);
        } catch (ActivityNotFoundException exception) {
            waitingForExternalPaymentReturn = false;
            showPaymentError(getString(R.string.recharge_payment_wechat_missing), true);
        } catch (SecurityException | IOException exception) {
            waitingForExternalPaymentReturn = false;
            showPaymentError(getString(R.string.recharge_payment_wechat_failed), true);
        }
    }

    /** 系统浏览器仅作为微信无法直接唤起时的人工备用方式，不再是默认支付链路。 */
    private void openCheckoutInSystemBrowser() {
        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(checkoutUrl));
        browser.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            waitingForExternalPaymentReturn = true;
            startActivity(browser);
        } catch (ActivityNotFoundException | SecurityException exception) {
            waitingForExternalPaymentReturn = false;
            showPaymentError(getString(R.string.recharge_payment_browser_failed), false);
        }
    }

    private void showPaymentError(String message, boolean showBrowserFallback) {
        if (isFinishing() || isDestroyed() || errorDialogShowing) return;
        errorDialogShowing = true;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.recharge_payment_error_title)
                .setMessage(message)
                .setOnDismissListener(ignored -> errorDialogShowing = false)
                .setNegativeButton(R.string.recharge_payment_close_plain, null);
        if (showBrowserFallback) {
            builder.setPositiveButton(
                    R.string.recharge_payment_browser_fallback_short,
                    (ignored, which) -> openCheckoutInSystemBrowser()
            );
        }
        builder.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedThemeState != AppThemeManager.state(this)) {
            recreate();
            return;
        }
        if (waitingForExternalPaymentReturn) {
            // 回到 App 只能证明外部支付页面已经离开，不能证明扣款成功。
            // 结束后由房间详情页查询官方订单，避免本页把“返回”冒充“支付成功”。
            waitingForExternalPaymentReturn = false;
            // 先在数据库记录“确实从外部支付页返回”，再通知父页面。即使父 Activity
            // 随后被系统重建，也只会恢复有 launched_at + returned_at 的真实支付流程。
            ReadingHistoryStore historyStore = new ReadingHistoryStore(this);
            try {
                historyStore.markRechargeAttemptReturned(attemptId);
            } catch (RuntimeException ignored) {
                // 父 Activity 收到 RESULT_OK 后还会再写一次 returned_at。这里失败不能让
                // 已经完成的外部支付流程闪退或被误当成用户取消。
            } finally {
                historyStore.close();
            }
            setResult(
                    RESULT_OK,
                    new Intent().putExtra(EXTRA_ATTEMPT_ID, attemptId)
            );
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(
                STATE_WAITING_FOR_EXTERNAL, waitingForExternalPaymentReturn
        );
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void applySystemBarInsets() {
        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    view.getPaddingLeft(), insets.getSystemWindowInsetTop(),
                    view.getPaddingRight(), insets.getSystemWindowInsetBottom()
            );
            return insets;
        });
        content.requestApplyInsets();
    }
}
