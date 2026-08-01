package com.shangzhili.electricityreminder;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 无需登录的“电小侠基地”：汇总本地房间状态，并集中承载全局工具与品牌信息。
 * 页面不会把房间号、别名、阈值或余额上传到服务器。
 */
public final class HeroBaseActivity extends Activity {
    private RoomRepository repository;
    private TextView versionStatusText;
    private ImageView mascotImage;
    private AnimatorSet mascotIdleAnimator;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppThemeManager.wrap(base));
    }

    @Override protected void onCreate(Bundle state) {
        AppThemeManager.apply(this);
        super.onCreate(state);
        setContentView(R.layout.activity_hero_base);
        getWindow().setStatusBarColor(getColor(R.color.base_background));
        getWindow().setNavigationBarColor(getColor(R.color.base_background));
        getWindow().getDecorView().setSystemUiVisibility(
                getWindow().getDecorView().getSystemUiVisibility()
                        & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        );
        repository = new RoomRepository(this);
        versionStatusText = findViewById(R.id.baseVersionText);
        mascotImage = findViewById(R.id.baseMascotImage);
        mascotImage.setOnClickListener(v -> playMascotReaction());
        findViewById(R.id.baseBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.baseThemeButton).setOnClickListener(v -> openSystemSettings());
        findViewById(R.id.baseSystemButton).setOnClickListener(v -> openSystemSettings());
        findViewById(R.id.baseUpdateButton).setOnClickListener(v -> {
            versionStatusText.setText("应用版本\n正在检查");
            ((ElecApplication) getApplication()).requestManualUpdate(this);
            queryLatestVersion();
        });
        findViewById(R.id.baseDataButton).setOnClickListener(v -> showInfo(
                "数据与存储", "房间配置、提醒规则和个人历史保存在本机；服务器公共历史仅作为补充。清除应用数据或卸载会删除本地内容。"));
        findViewById(R.id.baseHelpButton).setOnClickListener(v -> showInfo(
                "使用说明", "添加房间后可手动查询余额；开启监测后每个整点检查。余额不足会按房间设置的间隔复查并提醒。"));
        findViewById(R.id.baseFeedbackButton).setOnClickListener(v -> showFeedbackDialog());
        findViewById(R.id.baseGithubButton).setOnClickListener(v -> startActivity(new Intent(
                Intent.ACTION_VIEW, Uri.parse("https://github.com/zhisz/jiangli-electricity-manager"))));
        findViewById(R.id.basePrivacyButton).setOnClickListener(v -> showInfo(
                "隐私与数据说明", "App 不需要账号。房间号、别名、阈值和余额不会随反馈上传；反馈仅保存署名、正文、App 版本和服务器接收时间。"));
        ((TextView) findViewById(R.id.baseAboutVersionText)).setText(
                "当前版本　" + BuildConfig.VERSION_NAME);
        animateEntrance();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        queryLatestVersion();
        startMascotIdleAnimation();
    }

    @Override protected void onPause() {
        stopMascotAnimation();
        super.onPause();
    }

    private void animateEntrance() {
        View root = findViewById(R.id.heroBaseRoot);
        root.setAlpha(0f); root.setScaleX(.97f); root.setScaleY(.97f);
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).start();
    }

    private void refreshStatus() {
        List<String> ids = repository.listRoomIds();
        int monitoring = 0, low = 0;
        for (String id : ids) {
            if (repository.isMonitoringEnabled(id)) monitoring++;
            if (new MonitorState(this, id).isLowAlertActive()) low++;
        }
        SystemSettingsStatus system = SystemSettingsChecker.check(this);
        ((TextView) findViewById(R.id.baseMonitoringText)).setText("正在监测\n" + monitoring + " 个房间");
        ((TextView) findViewById(R.id.baseLowText)).setText("余额不足\n" + low + " 个房间");
        ((TextView) findViewById(R.id.baseSystemText)).setText(
                system.allReady() ? "系统权限\n全部就绪" : "系统权限\n缺 " + system.missingCount() + " 项");
        String period = greetingPeriod();
        TextView greeting = findViewById(R.id.baseGreetingText);
        TextView mascotState = findViewById(R.id.baseMascotStateText);
        if (low > 0) {
            greeting.setText(period + "，有 " + low + " 个房间需要注意余额");
            mascotState.setText("⚡ 提醒状态"); mascotImage.setAlpha(1f);
        } else if (!system.allReady()) {
            greeting.setText(period + "，还有系统设置需要处理");
            mascotState.setText("🔧 工具维修状态"); mascotImage.setAlpha(.9f);
        } else if (monitoring == 0) {
            greeting.setText(period + "，还没有房间开启监测");
            mascotState.setText("休息状态"); mascotImage.setAlpha(.78f);
        } else {
            greeting.setText(period + "，宿舍电量一切正常\n还有 " + monitoring + " 个房间正在监测");
            mascotState.setText("⚡ 精神饱满"); mascotImage.setAlpha(1f);
        }
    }

    /**
     * 桌面宠物式的待机动作只变换 View，不逐帧解码图片，页面离开即停止，几乎不增加
     * 后台开销。系统关闭动画时完全尊重用户设置，不强行播放。
     */
    private void startMascotIdleAnimation() {
        stopMascotAnimation();
        if (!animationsEnabled()) return;
        float lift = -8 * getResources().getDisplayMetrics().density;
        ObjectAnimator floatY = ObjectAnimator.ofFloat(mascotImage, View.TRANSLATION_Y, 0, lift, 0);
        floatY.setDuration(2_400); floatY.setRepeatCount(ObjectAnimator.INFINITE);
        ObjectAnimator breatheX = ObjectAnimator.ofFloat(mascotImage, View.SCALE_X, 1f, 1.025f, 1f);
        ObjectAnimator breatheY = ObjectAnimator.ofFloat(mascotImage, View.SCALE_Y, 1f, 1.025f, 1f);
        breatheX.setDuration(2_400); breatheY.setDuration(2_400);
        breatheX.setRepeatCount(ObjectAnimator.INFINITE); breatheY.setRepeatCount(ObjectAnimator.INFINITE);
        mascotIdleAnimator = new AnimatorSet();
        mascotIdleAnimator.playTogether(floatY, breatheX, breatheY);
        mascotIdleAnimator.start();
    }

    /** 点击角色时做一次短促起跳，随后自然回到待机循环，形成可互动但不打扰的感觉。 */
    private void playMascotReaction() {
        if (!animationsEnabled()) return;
        stopMascotAnimation();
        float hop = -18 * getResources().getDisplayMetrics().density;
        AnimatorSet reaction = new AnimatorSet();
        reaction.playTogether(
                ObjectAnimator.ofFloat(mascotImage, View.TRANSLATION_Y, 0, hop, 0),
                ObjectAnimator.ofFloat(mascotImage, View.ROTATION, 0, -3f, 3f, 0),
                ObjectAnimator.ofFloat(mascotImage, View.SCALE_X, 1f, 1.06f, 1f),
                ObjectAnimator.ofFloat(mascotImage, View.SCALE_Y, 1f, .96f, 1f)
        );
        reaction.setDuration(420);
        reaction.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { startMascotIdleAnimation(); }
        });
        reaction.start();
    }

    private void stopMascotAnimation() {
        if (mascotIdleAnimator != null) mascotIdleAnimator.cancel();
        mascotIdleAnimator = null;
        if (mascotImage != null) {
            mascotImage.setTranslationY(0); mascotImage.setRotation(0);
            mascotImage.setScaleX(1); mascotImage.setScaleY(1);
        }
    }

    private boolean animationsEnabled() {
        try {
            return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Exception ignored) { return true; }
    }

    private String greetingPeriod() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour < 6 ? "夜深了" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
    }

    private void queryLatestVersion() {
        String url = BuildConfig.UPDATE_MANIFEST_URL;
        if (url == null || url.trim().isEmpty()) {
            versionStatusText.setText("应用版本\n" + BuildConfig.VERSION_NAME); return;
        }
        new Thread(() -> {
            try {
                UpdateInfo info = new AppUpdateClient().query(url);
                String value = info == null || info.versionCode <= BuildConfig.VERSION_CODE
                        ? "应用版本\n已是最新版" : "应用版本\n可更新至 " + info.versionName;
                runOnUiThread(() -> versionStatusText.setText(value));
            } catch (Exception ignored) {
                runOnUiThread(() -> versionStatusText.setText("应用版本\n" + BuildConfig.VERSION_NAME));
            }
        }, "base-version-check").start();
    }

    private void showFeedbackDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, 0, pad, 0);
        EditText signature = new EditText(this); signature.setHint("署名（必填）"); signature.setMaxLines(1);
        EditText content = new EditText(this); content.setHint("请写下你的意见或遇到的问题（必填）");
        content.setMinLines(4); content.setMaxLines(8); content.setGravity(android.view.Gravity.TOP);
        form.addView(signature); form.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("意见反馈")
                .setMessage("反馈会发送到开发者后台，不会附带房间信息。")
                .setView(form).setNegativeButton("取消", null).setPositiveButton("发送", null).create();
        dialog.setOnShowListener(v -> {
            Button sendButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            sendButton.setOnClickListener(button -> {
            String name = signature.getText().toString().trim();
            String text = content.getText().toString().trim();
            if (name.isEmpty()) { signature.setError("请填写署名"); return; }
            if (text.isEmpty()) { content.setError("请填写反馈内容"); return; }
            if (name.length() > 40 || text.length() > 2000) { toast("署名或反馈内容过长"); return; }
            sendButton.setEnabled(false); sendButton.setText("发送中…");
            new Thread(() -> {
                try {
                    new FeedbackClient().submit(name, text);
                    runOnUiThread(() -> { dialog.dismiss(); toast("反馈已送达，谢谢你的署名反馈"); });
                } catch (Exception error) {
                    runOnUiThread(() -> { sendButton.setEnabled(true); sendButton.setText("发送"); toast(error.getMessage()); });
                }
            }, "feedback-submit").start();
            });
        });
        dialog.show();
    }

    private void openSystemSettings() { startActivity(new Intent(this, SystemSettingsActivity.class)); }
    private void showInfo(String title, String message) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show(); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
