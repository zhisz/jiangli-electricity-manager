package com.shangzhili.electricityreminder;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;

/**
 * 电小侠基地的内容与交互控制器。
 *
 * <p>基地既可以嵌在首页侧滑层中，也可由独立 Activity 承载。所有状态、反馈、主题切换
 * 和角色动画只维护一份，避免两个入口出现显示或行为差异。</p>
 */
final class HeroBaseController {
    private final Activity activity;
    private final View root;
    private final Runnable closeAction;
    private final RoomRepository repository;
    private final TextView versionStatusText;
    private final ImageView mascotImage;
    private AnimatorSet mascotIdleAnimator;
    private int quoteIndex = -1;

    HeroBaseController(Activity activity, View root, Runnable closeAction) {
        this.activity = activity;
        this.root = root;
        this.closeAction = closeAction;
        repository = new RoomRepository(activity);
        versionStatusText = root.findViewById(R.id.baseVersionText);
        mascotImage = root.findViewById(R.id.baseMascotImage);

        mascotImage.setOnClickListener(view -> playMascotReaction());
        root.findViewById(R.id.baseBackButton).setOnClickListener(view -> closeAction.run());
        root.findViewById(R.id.baseSettingsButton).setOnClickListener(view ->
                activity.startActivity(new Intent(activity, SystemSettingsActivity.class)));
        root.findViewById(R.id.baseAppearanceButton).setOnClickListener(view -> toggleAppearance());
        root.findViewById(R.id.baseFeedbackButton).setOnClickListener(view -> showFeedbackDialog());
        root.findViewById(R.id.baseGithubButton).setOnClickListener(view -> activity.startActivity(
                new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/zhisz/jiangli-electricity-manager"))));
        root.findViewById(R.id.basePrivacyButton).setOnClickListener(view -> showInfo(
                "隐私与数据说明",
                "App 不需要账号。房间号、别名、阈值和余额不会随反馈上传；反馈仅保存署名、正文、App 版本和服务器接收时间。"
        ));
        ((TextView) root.findViewById(R.id.baseAboutVersionText)).setText(
                "当前版本　" + BuildConfig.VERSION_NAME);
        refreshAppearanceButton();
    }

    void onVisible() {
        refreshStatus();
        queryLatestVersion();
        startMascotIdleAnimation();
    }

    void onHidden() { stopMascotAnimation(); }

    private void refreshStatus() {
        List<String> ids = repository.listRoomIds();
        int monitoring = 0;
        int low = 0;
        for (String id : ids) {
            if (repository.isMonitoringEnabled(id)) monitoring++;
            if (new MonitorState(activity, id).isLowAlertActive()) low++;
        }
        SystemSettingsStatus system = SystemSettingsChecker.check(activity);
        ((TextView) root.findViewById(R.id.baseMonitoringText))
                .setText("正在监测\n" + monitoring + " 个房间");
        ((TextView) root.findViewById(R.id.baseLowText))
                .setText("余额不足\n" + low + " 个房间");
        ((TextView) root.findViewById(R.id.baseSystemText)).setText(
                system.allReady() ? "系统权限\n全部就绪" : "系统权限\n缺 " + system.missingCount() + " 项");

        TextView greeting = root.findViewById(R.id.baseGreetingText);
        TextView mascotState = root.findViewById(R.id.baseMascotStateText);
        String period = greetingPeriod();
        if (low > 0) {
            greeting.setText(period + "，有 " + low + " 个房间需要注意余额");
            mascotState.setText("⚡ 提醒状态");
            mascotImage.setAlpha(1f);
        } else if (!system.allReady()) {
            greeting.setText(period + "，还有系统设置需要处理");
            mascotState.setText("🔧 工具维修状态");
            mascotImage.setAlpha(.9f);
        } else if (monitoring == 0) {
            greeting.setText(period + "，还没有房间开启监测");
            mascotState.setText("休息状态");
            mascotImage.setAlpha(.78f);
        } else {
            greeting.setText(period + "，宿舍电量一切正常\n还有 " + monitoring + " 个房间正在监测");
            mascotState.setText("⚡ 精神饱满");
            mascotImage.setAlpha(1f);
        }
    }

    private void startMascotIdleAnimation() {
        stopMascotAnimation();
        if (!animationsEnabled()) return;
        float lift = -8 * activity.getResources().getDisplayMetrics().density;
        ObjectAnimator floatY = ObjectAnimator.ofFloat(
                mascotImage, View.TRANSLATION_Y, 0, lift, 0);
        floatY.setDuration(2_400);
        floatY.setRepeatCount(ObjectAnimator.INFINITE);
        ObjectAnimator breatheX = ObjectAnimator.ofFloat(
                mascotImage, View.SCALE_X, 1f, 1.025f, 1f);
        ObjectAnimator breatheY = ObjectAnimator.ofFloat(
                mascotImage, View.SCALE_Y, 1f, 1.025f, 1f);
        breatheX.setDuration(2_400);
        breatheY.setDuration(2_400);
        breatheX.setRepeatCount(ObjectAnimator.INFINITE);
        breatheY.setRepeatCount(ObjectAnimator.INFINITE);
        mascotIdleAnimator = new AnimatorSet();
        mascotIdleAnimator.playTogether(floatY, breatheX, breatheY);
        mascotIdleAnimator.start();
    }

    private void playMascotReaction() {
        quoteIndex = (quoteIndex + 1) % HeroQuotes.size();
        ((TextView) root.findViewById(R.id.baseGreetingText)).setText(HeroQuotes.at(quoteIndex));
        if (!animationsEnabled()) return;
        stopMascotAnimation();
        float hop = -18 * activity.getResources().getDisplayMetrics().density;
        AnimatorSet reaction = new AnimatorSet();
        reaction.playTogether(
                ObjectAnimator.ofFloat(mascotImage, View.TRANSLATION_Y, 0, hop, 0),
                ObjectAnimator.ofFloat(mascotImage, View.ROTATION, 0, -3f, 3f, 0),
                ObjectAnimator.ofFloat(mascotImage, View.SCALE_X, 1f, 1.06f, 1f),
                ObjectAnimator.ofFloat(mascotImage, View.SCALE_Y, 1f, .96f, 1f)
        );
        reaction.setDuration(420);
        reaction.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                startMascotIdleAnimation();
            }
        });
        reaction.start();
    }

    private void stopMascotAnimation() {
        if (mascotIdleAnimator != null) mascotIdleAnimator.cancel();
        mascotIdleAnimator = null;
        mascotImage.setTranslationY(0);
        mascotImage.setRotation(0);
        mascotImage.setScaleX(1);
        mascotImage.setScaleY(1);
    }

    private boolean animationsEnabled() {
        try {
            return Settings.Global.getFloat(
                    activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Exception ignored) {
            return true;
        }
    }

    private String greetingPeriod() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour < 6 ? "夜深了" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
    }

    private void queryLatestVersion() {
        String url = BuildConfig.UPDATE_MANIFEST_URL;
        if (url == null || url.trim().isEmpty()) {
            versionStatusText.setText("应用版本\n" + BuildConfig.VERSION_NAME);
            return;
        }
        new Thread(() -> {
            try {
                UpdateInfo info = new AppUpdateClient().query(url);
                String value = info == null || info.versionCode <= BuildConfig.VERSION_CODE
                        ? "应用版本\n已是最新版" : "应用版本\n可更新至 " + info.versionName;
                activity.runOnUiThread(() -> versionStatusText.setText(value));
            } catch (Exception ignored) {
                activity.runOnUiThread(() -> versionStatusText.setText(
                        "应用版本\n" + BuildConfig.VERSION_NAME));
            }
        }, "base-version-check").start();
    }

    private void showFeedbackDialog() {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(pad, 0, pad, 0);
        EditText signature = new EditText(activity);
        signature.setHint("署名（必填）");
        signature.setMaxLines(1);
        EditText content = new EditText(activity);
        content.setHint("请写下你的意见或遇到的问题（必填）");
        content.setMinLines(4);
        content.setMaxLines(8);
        content.setGravity(android.view.Gravity.TOP);
        ColorStateList fieldTint = ColorStateList.valueOf(activity.getColor(R.color.purple_primary));
        for (EditText field : new EditText[]{signature, content}) {
            field.setTextColor(activity.getColor(R.color.text_primary));
            field.setHintTextColor(activity.getColor(R.color.text_tertiary));
            field.setBackgroundTintList(fieldTint);
        }
        form.addView(signature);
        form.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.BaseDialogTheme)
                .setTitle("意见反馈")
                .setMessage("反馈会发送到开发者后台，不会附带房间信息。")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("发送", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button sendButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            sendButton.setOnClickListener(button -> submitFeedback(
                    dialog, sendButton, signature, content));
        });
        dialog.show();
    }

    private void submitFeedback(
            AlertDialog dialog, Button sendButton, EditText signature, EditText content
    ) {
        String name = signature.getText().toString().trim();
        String text = content.getText().toString().trim();
        if (name.isEmpty()) {
            signature.setError("请填写署名");
            return;
        }
        if (text.isEmpty()) {
            content.setError("请填写反馈内容");
            return;
        }
        if (name.length() > 40 || text.length() > 2000) {
            toast("署名或反馈内容过长");
            return;
        }
        sendButton.setEnabled(false);
        sendButton.setText("发送中…");
        new Thread(() -> {
            try {
                new FeedbackClient().submit(name, text);
                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    toast("反馈已闪电送达，感谢 " + name + " 的反馈");
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    sendButton.setEnabled(true);
                    sendButton.setText("发送");
                    toast(error.getMessage());
                });
            }
        }, "feedback-submit").start();
    }

    private void showInfo(String title, String message) {
        new AlertDialog.Builder(activity, R.style.BaseDialogTheme)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void toggleAppearance() {
        AppThemeManager.saveAppearance(activity, isCurrentlyDark()
                ? AppThemeManager.APPEARANCE_LIGHT : AppThemeManager.APPEARANCE_DARK);
        activity.recreate();
    }

    private void refreshAppearanceButton() {
        Button button = root.findViewById(R.id.baseAppearanceButton);
        button.setText(isCurrentlyDark() ? "☀　浅色" : "☾　深色");
    }

    private boolean isCurrentlyDark() {
        return (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void toast(String value) {
        Toast.makeText(activity, value, Toast.LENGTH_LONG).show();
    }
}
