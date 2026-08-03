package com.shangzhili.electricityreminder;

import android.annotation.SuppressLint;
import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 首页只负责展示用户已经添加的房间；每个房间的查询和规则位于详情页。 */
public final class MainActivity extends Activity {
    private static final String STATE_HERO_BASE_OPEN = "heroBaseOpen";
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppThemeManager.wrap(newBase));
    }

    /** 同时记录品牌色与深浅模式，返回首页时用于判断是否需要无闪烁地重建页面。 */
    private int appliedThemeState;
    private RoomRepository repository;
    private LinearLayout roomsContainer;
    private TextView emptyText;
    private TextView roomCountText;
    private TextView reorderHintText;
    private Button systemSettingsButton;
    private SetupPreferences setupPreferences;
    private AlertDialog firstUseGuide;
    private InteractiveDrawerLayout drawerLayout;
    private HeroBaseController heroBaseController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedThemeState = AppThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room_list);
        applySystemBarInsets();
        repository = new RoomRepository(this);
        setupPreferences = new SetupPreferences(this);
        // 设置检查依赖通知渠道是否存在，因此首页启动时先创建渠道，再读取系统状态。
        new NotificationHelper(this);
        // 首次升级完成旧配置迁移后，立即为迁移出的房间建立带 roomId 的新闹钟。
        Scheduler.restoreAllConfigured(this);
        roomsContainer = findViewById(R.id.roomsContainer);
        emptyText = findViewById(R.id.emptyText);
        roomCountText = findViewById(R.id.roomCountText);
        reorderHintText = findViewById(R.id.reorderHintText);
        systemSettingsButton = findViewById(R.id.systemSettingsButton);

        drawerLayout = findViewById(R.id.mainDrawerLayout);
        View heroPanel = findViewById(R.id.heroBasePanel);
        drawerLayout.bind(heroPanel, findViewById(R.id.drawerScrim));
        heroBaseController = new HeroBaseController(
                this, heroPanel, () -> drawerLayout.closeDrawer(true));
        /*
         * 深浅色切换通过 recreate() 重新读取 values/values-night。先恢复抽屉状态、再注册
         * 状态监听，首次布局便会直接画出展开的基地，也不会重复启动两次角色动画或版本查询。
         */
        if (savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_HERO_BASE_OPEN, false)) {
            drawerLayout.openDrawer(false);
        }
        drawerLayout.setDrawerStateListener(open -> {
            if (open) heroBaseController.onVisible();
            else heroBaseController.onHidden();
        });

        systemSettingsButton.setOnClickListener(view -> openSystemSettings());
        findViewById(R.id.mascotBaseButton).setOnClickListener(this::openHeroBase);

        findViewById(R.id.addRoomButton).setOnClickListener(view ->
                new AddRoomDialog(this, repository, roomId ->
                        showMonitoringGuideForNewRoom(roomId)
                ).show()
        );

        // 首次安装只展示一次说明。用户以后仍可从首页右上角重新进入设置检查。
        showFirstUseGuideIfNeeded();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleBack
            );
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedThemeState != AppThemeManager.state(this)) {
            recreate();
            return;
        }
        renderRooms();
        refreshSystemSettingsStatus();
        if (drawerLayout.isDrawerOpen()) heroBaseController.onVisible();
    }

    @Override protected void onPause() {
        if (heroBaseController != null) heroBaseController.onHidden();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_HERO_BASE_OPEN,
                drawerLayout != null && drawerLayout.isDrawerOpen());
        super.onSaveInstanceState(outState);
    }

    /**
     * 只在 AddRoomDialog 首次创建成功的回调中显示，因此刷新、改备注和重新进入详情都不会
     * 重复打扰。选择“暂不开启”时配置已完整保存，实时查询、云端历史和图表均不受影响。
     */
    private void showMonitoringGuideForNewRoom(String roomId) {
        renderRooms();
        new MaterialAlertDialogBuilder(this)
                .setTitle("房间已添加")
                .setMessage("是否现在开启余额监测与低余额提醒？")
                .setCancelable(false)
                .setNegativeButton("暂不开启", (dialog, which) ->
                        startActivity(RoomDetailActivity.createIntent(this, roomId))
                )
                .setPositiveButton("现在设置", (dialog, which) -> {
                    /*
                     * 一次性建立“首页 → 详情 → 设置”的返回栈。设置页无论是否保存，按返回键
                     * 都只会回到刚添加房间的详情，而不是越过详情直接回首页。
                     */
                    startActivities(new Intent[]{
                            RoomDetailActivity.createIntent(this, roomId),
                            RoomSettingsActivity.createIntent(this, roomId)
                    });
                })
                .show();
    }

    /**
     * 首页入口直接显示缺失项数量，让用户无需进入详情页就能发现后台提醒环境异常。
     * 厂商自启动没有统一的 Android 查询接口，所以该项以用户在设置中心的确认为准。
     */
    private void refreshSystemSettingsStatus() {
        SystemSettingsStatus status = SystemSettingsChecker.check(this);
        systemSettingsButton.setText(status.allReady()
                ? "● 设置" : "● 设置 · 缺 " + status.missingCount() + " 项");
        // 圆点与文字共同表达状态；即使用户无法区分颜色，缺失项数字仍然清晰可见。
        systemSettingsButton.setTextColor(getColor(status.allReady()
                ? R.color.status_normal : R.color.status_warning));
    }

    private void showFirstUseGuideIfNeeded() {
        if (setupPreferences.hasShownOnboarding()) {
            showFormalReleaseMigrationNoticeIfNeeded();
            return;
        }
        // 先记录“已展示”，避免旋转屏幕或系统重建 Activity 时连续弹出多个对话框。
        setupPreferences.markOnboardingShown();
        firstUseGuide = new MaterialAlertDialogBuilder(this)
                .setTitle("首次使用设置")
                .setMessage("为了在 App 退出后仍能按时提醒，需要检查通知权限、精确闹钟、"
                        + "电池优化和厂商自启动。\n\n"
                        + "江理电小侠会逐项显示当前状态，并提供对应的系统设置入口。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("检查并设置", (dialog, which) -> openSystemSettings())
                .create();
        firstUseGuide.setOnDismissListener(dialog -> {
            firstUseGuide = null;
            showFormalReleaseMigrationNoticeIfNeeded();
            ((ElecApplication) getApplication()).requestUpdateWhenSafe(this);
        });
        firstUseGuide.show();
    }

    /**
     * 2.0.0 同时更换包名和正式签名，Android 会把它视为全新应用，旧调试版无法被覆盖。
     * 提示只显示一次；新用户也能直接理解为“若桌面存在旧版才需处理”，不会被强制操作。
     */
    private void showFormalReleaseMigrationNoticeIfNeeded() {
        if (setupPreferences.hasShownFormalReleaseNotice()) return;
        setupPreferences.markFormalReleaseNoticeShown();
        new MaterialAlertDialogBuilder(this)
                .setTitle("已切换到备案正式版")
                .setMessage("如果桌面上还保留旧调试版“江理电小侠”，请先确认当前正式版可以正常打开，随后手动删除旧版。\n\n"
                        + "由于 Android 不允许不同包名和签名共享本地数据，正式版需要重新添加房间。以后更新会直接覆盖正式版，不再需要重复迁移。")
                .setPositiveButton("我知道了", null)
                .show();
    }

    boolean isFirstUseGuideShowing() {
        return firstUseGuide != null && firstUseGuide.isShowing();
    }

    private void openSystemSettings() {
        startActivity(new Intent(this, SystemSettingsActivity.class));
    }

    /** 点击头像与手势共用同一个侧滑层，避免出现两套不同的进入动画和返回栈。 */
    private void openHeroBase(View mascot) {
        if (!mascot.isEnabled()) return;
        mascot.setEnabled(false);
        mascot.animate().scaleX(1.12f).scaleY(1.12f).setDuration(100).withEndAction(() -> {
            drawerLayout.openDrawer(true);
            mascot.animate().scaleX(1f).scaleY(1f).setDuration(140)
                    .withEndAction(() -> mascot.setEnabled(true)).start();
        }).start();
    }

    private void handleBack() {
        if (drawerLayout.isDrawerOpen()) drawerLayout.closeDrawer(true);
        else finish();
    }

    /** Android 8～12 的返回键兼容路径；Android 13+ 由预测性返回回调处理。 */
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { handleBack(); }

    private void renderRooms() {
        roomsContainer.removeAllViews();
        List<String> roomIds = repository.listRoomIds();
        emptyText.setVisibility(roomIds.isEmpty() ? View.VISIBLE : View.GONE);
        reorderHintText.setVisibility(roomIds.isEmpty() ? View.GONE : View.VISIBLE);
        roomCountText.setText(String.format(Locale.CHINA, "共 %d 个房间", roomIds.size()));

        LayoutInflater inflater = LayoutInflater.from(this);
        for (String roomId : roomIds) {
            AppConfig config = repository.load(roomId);
            MonitorState state = new MonitorState(this, roomId);
            View card = inflater.inflate(R.layout.item_room, roomsContainer, false);

            ((TextView) card.findViewById(R.id.roomAliasText)).setText(config.alias);
            applyBalance(card, state);
            boolean monitoringEnabled = repository.isMonitoringEnabled(roomId);
            ((TextView) card.findViewById(R.id.roomMetaText)).setText(String.format(
                    Locale.CHINA,
                    monitoringEnabled
                            ? "每整点监测 · 提醒阈值 %.2f%s"
                            : "整点监测已暂停 · 提醒阈值 %.2f%s",
                    config.threshold, "amount".equals(config.metric) ? " 元" : " 度"
            ));

            TextView status = card.findViewById(R.id.roomStatusText);
            applyStatus(status, state, monitoringEnabled);
            // 快捷按钮是一块独立触控区域：单击直接进入充值金额选择，不需要先打开详情
            // 再寻找充值入口；卡片其余区域仍保留点击详情和长按实时排序。
            card.findViewById(R.id.roomRechargeButton).setOnClickListener(view ->
                    startActivity(RoomDetailActivity.createRechargeIntent(this, roomId))
            );
            card.setOnClickListener(view ->
                    startActivity(RoomDetailActivity.createIntent(this, roomId))
            );
            attachReorderTouchHandler(card, roomId);
            roomsContainer.addView(card);
        }
    }

    /**
     * 使用自定义触摸排序，不调用 startDragAndDrop，因而不会生成系统的半透明拖拽残影。
     * 长按后移动的是卡片 View 本身；被跨过的卡片会同步平移让位，松手后再保存最终顺序。
     */
    private void attachReorderTouchHandler(View card, String roomId) {
        card.setOnTouchListener(new ReorderTouchListener(card, roomId));
    }

    /** 负责一张房间卡片从长按识别、实时让位到落位保存的完整手势。 */
    private final class ReorderTouchListener implements View.OnTouchListener {
        private static final long SETTLE_DURATION_MILLIS = 150;
        private final View card;
        private final String roomId;
        private final int touchSlop;
        private final Runnable beginReorder = this::beginReorder;
        private float downRawX;
        private float downRawY;
        private boolean reordering;
        private boolean movedBeforeLongPress;
        private int sourceIndex = -1;
        private int targetIndex = -1;

        private ReorderTouchListener(View card, String roomId) {
            this.card = card;
            this.roomId = roomId;
            touchSlop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    reordering = false;
                    movedBeforeLongPress = false;
                    // 沿用系统规定的长按时长，手感与其他 Android 列表保持一致。
                    card.postDelayed(beginReorder, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (reordering) {
                        updateReorder(event.getRawY());
                    } else if (distanceFromDown(event) > touchSlop) {
                        // 用户在长按生效前滑动时，把动作交给外层 ScrollView 作为普通滚动。
                        movedBeforeLongPress = true;
                        card.removeCallbacks(beginReorder);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    card.removeCallbacks(beginReorder);
                    if (reordering) finishReorder();
                    // 短列表无法滚动时父容器可能不会拦截 MOVE；此时也不能把滑动误判成点击。
                    else if (!movedBeforeLongPress) card.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    card.removeCallbacks(beginReorder);
                    if (reordering) finishReorder();
                    return true;
                default:
                    return true;
            }
        }

        private float distanceFromDown(MotionEvent event) {
            return (float) Math.hypot(
                    event.getRawX() - downRawX, event.getRawY() - downRawY
            );
        }

        private void beginReorder() {
            sourceIndex = roomsContainer.indexOfChild(card);
            if (sourceIndex < 0) return;
            targetIndex = sourceIndex;
            reordering = true;
            card.setPressed(false);
            card.setElevation(dp(12));
            card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            // 排序期间禁止 ScrollView 抢走后续 MOVE 事件，否则卡片会在中途停止跟手。
            card.getParent().requestDisallowInterceptTouchEvent(true);
        }

        private void updateReorder(float rawY) {
            float translation = rawY - downRawY;
            card.setTranslationY(translation);
            float movingCenter = card.getTop() + translation + card.getHeight() / 2f;
            int newTarget = sourceIndex;

            if (translation > 0) {
                for (int index = sourceIndex + 1; index < roomsContainer.getChildCount(); index++) {
                    View sibling = roomsContainer.getChildAt(index);
                    if (movingCenter > sibling.getTop() + sibling.getHeight() / 2f) {
                        newTarget = index;
                    }
                }
            } else {
                for (int index = sourceIndex - 1; index >= 0; index--) {
                    View sibling = roomsContainer.getChildAt(index);
                    if (movingCenter < sibling.getTop() + sibling.getHeight() / 2f) {
                        newTarget = index;
                    }
                }
            }
            if (newTarget != targetIndex) {
                targetIndex = newTarget;
                shiftSiblingsForTarget();
                card.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }

        /** 被拖动卡片跨过的所有卡片实时向空位移动，未跨过的卡片回到原位。 */
        private void shiftSiblingsForTarget() {
            int sourceSlotHeight = totalSlotHeight(card);
            for (int index = 0; index < roomsContainer.getChildCount(); index++) {
                View sibling = roomsContainer.getChildAt(index);
                if (sibling == card) continue;
                float shift = 0;
                if (targetIndex > sourceIndex && index > sourceIndex && index <= targetIndex) {
                    shift = -sourceSlotHeight;
                } else if (targetIndex < sourceIndex
                        && index >= targetIndex && index < sourceIndex) {
                    shift = sourceSlotHeight;
                }
                sibling.animate().translationY(shift).setDuration(100).start();
            }
        }

        private void finishReorder() {
            reordering = false;
            card.getParent().requestDisallowInterceptTouchEvent(false);
            // 卡片先吸附到最终空位，再重建列表；视觉上不会先弹回原位后突然跳动。
            card.animate()
                    .translationY(finalSlotTranslation())
                    .setDuration(SETTLE_DURATION_MILLIS)
                    .start();
            repository.moveRoomToIndex(roomId, targetIndex);
            roomsContainer.postDelayed(
                    MainActivity.this::renderRooms, SETTLE_DURATION_MILLIS
            );
        }

        private float finalSlotTranslation() {
            float result = 0;
            if (targetIndex > sourceIndex) {
                for (int index = sourceIndex + 1; index <= targetIndex; index++) {
                    result += totalSlotHeight(roomsContainer.getChildAt(index));
                }
            } else if (targetIndex < sourceIndex) {
                for (int index = targetIndex; index < sourceIndex; index++) {
                    result -= totalSlotHeight(roomsContainer.getChildAt(index));
                }
            }
            return result;
        }

        private int totalSlotHeight(View view) {
            int height = view.getHeight();
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                height += margins.topMargin + margins.bottomMargin;
            }
            return height;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    /** 将两种余额拆成主次两级，避免把关键数据塞进一段换行字符串。 */
    private void applyBalance(View card, MonitorState state) {
        TextView surplus = card.findViewById(R.id.roomSurplusText);
        TextView amount = card.findViewById(R.id.roomAmountText);
        TextView updated = card.findViewById(R.id.roomUpdatedText);
        if (state.getLastSuccessAt() <= 0) {
            surplus.setText("-- 度");
            amount.setText("约 -- 元");
            updated.setText("尚未查询，点击卡片获取余额");
            return;
        }
        DateFormat format = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT, Locale.CHINA
        );
        surplus.setText(String.format(Locale.CHINA, "%.2f 度", state.getLastSurplus()));
        amount.setText(String.format(Locale.CHINA, "约 %.2f 元", state.getLastAmount()));
        updated.setText("更新于 " + format.format(new Date(state.getLastSuccessAt())));
    }

    private void applyStatus(TextView view, MonitorState state, boolean monitoringEnabled) {
        if (!monitoringEnabled) {
            view.setText("监测暂停");
            applyStatusStyle(view, R.drawable.status_pill_neutral, R.color.text_secondary);
        } else if (!state.getLastError().isEmpty()) {
            view.setText("监测异常");
            applyStatusStyle(view, R.drawable.status_pill_danger, R.color.status_danger);
        } else if (state.isLowAlertActive()) {
            view.setText("余额不足");
            applyStatusStyle(view, R.drawable.status_pill_warning, R.color.status_warning);
        } else if (state.getLastSuccessAt() <= 0) {
            view.setText("等待查询");
            applyStatusStyle(view, R.drawable.status_pill_neutral, R.color.text_secondary);
        } else {
            view.setText("余额正常");
            applyStatusStyle(view, R.drawable.status_pill_normal, R.color.status_normal);
        }
    }

    private void applyStatusStyle(TextView view, int background, int textColor) {
        view.setBackgroundResource(background);
        view.setTextColor(getColor(textColor));
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
