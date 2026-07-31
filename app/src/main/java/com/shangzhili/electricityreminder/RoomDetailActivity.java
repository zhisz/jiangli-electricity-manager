package com.shangzhili.electricityreminder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 单个房间的实时余额、状态、趋势和提醒配置页面。 */
public final class RoomDetailActivity extends Activity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppThemeManager.wrap(newBase));
    }

    private static final String EXTRA_ROOM_ID = "roomId";
    private static final int REQUEST_RECHARGE_PAYMENT = 1001;
    /** 校付宝订单记录可能短暂延迟，使用逐步拉长的官方状态查询，最多等待两分钟。 */
    private static final long[] RECHARGE_VERIFY_DELAYS = {
            0, 3_000, 8_000, 15_000, 30_000, 60_000, 120_000
    };
    private static final long RECHARGE_VERIFY_DEADLINE =
            RECHARGE_VERIFY_DELAYS[RECHARGE_VERIFY_DELAYS.length - 1];
    /** Handler 的正常调度抖动留少量余量；不能让排队已久的最后一次请求无限延长检测。 */
    private static final long RECHARGE_FINAL_QUERY_START_GRACE = 2_000;
    /** 进程被系统回收后，仅恢复最近 30 分钟内尚未确认的支付尝试。 */
    private static final long RECHARGE_ATTEMPT_MAX_AGE = 30 * 60 * 1_000L;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler rechargeVerificationHandler = new Handler(Looper.getMainLooper());
    private final ElectricityRechargeClient rechargeClient =
            new ElectricityRechargeClient();
    private int appliedThemeState;
    private RoomRepository repository;
    private ReadingHistoryStore historyStore;
    private MonitorState monitorState;
    private String roomId;
    private boolean savedRoom;
    private boolean automaticQueryStarted;

    private TextView detailTitle;
    private TextView balanceSurplusText;
    private TextView balanceAmountText;
    private TextView balanceUpdatedText;
    private TextView balanceStateText;
    private TextView monitorStatusText;
    private TextView scheduleText;
    private TextView trendTitleText;
    private TextView historySummaryText;
    private TextView dailyUsageDetailText;
    private ElectricityTrendView trendView;
    private BalanceTrendAxisView balanceTrendAxisView;
    private DailyUsageBarView dailyUsageBarView;
    private DailyUsageAxisView dailyUsageAxisView;
    private HorizontalScrollView balanceTrendScrollView;
    private HorizontalScrollView dailyUsageScrollView;
    private EditText aliasInput;
    private EditText thresholdInput;
    private EditText recoveryInput;
    private EditText repeatMinutesInput;
    private RadioButton amountRadio;
    private Button queryButton;
    private Button saveButton;
    private Button cancelMonitoringButton;
    private Button deleteButton;
    private Button rechargeRecordsButton;
    private Button onlineRechargeButton;
    private Button saveAliasButton;
    private Button reminderSettingsHeaderButton;
    private LinearLayout balanceTrendContainer;
    private LinearLayout dailyUsageContainer;
    private LinearLayout reminderSettingsContent;
    private LinearLayout quickActionsContainer;
    private List<HistoryPoint> latestHistoryPoints = new ArrayList<>();
    private List<RechargeRecord> latestRechargeRecords = new ArrayList<>();
    private int dailyUsageDays = 30;
    /** 防止快速连点并发申请两个一次性令牌或创建两个充值订单。 */
    private boolean rechargeBusy;
    private boolean rechargeVerificationRunning;
    private boolean activityResumed;
    private int rechargeVerificationGeneration;
    /** 本轮成功读取官方订单接口的次数，用于区分“仍在同步”和“网络完全不可用”。 */
    private int rechargeVerificationSuccessfulQueries;
    private AlertDialog rechargeVerificationDialog;
    /** 已从数据库原子领取、但用户尚未确认看过的充值结果。 */
    private RechargeAttempt claimedRechargeResultNotice;
    private AlertDialog rechargeResultDialog;
    private boolean rechargeResultAcknowledged;

    public static Intent createIntent(Context context, String roomId) {
        return new Intent(context, RoomDetailActivity.class)
                .putExtra(EXTRA_ROOM_ID, roomId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedThemeState = AppThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets();

        roomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        if (roomId == null || roomId.isEmpty()) {
            finish();
            return;
        }

        repository = new RoomRepository(this);
        historyStore = new ReadingHistoryStore(this);
        savedRoom = repository.contains(roomId);
        monitorState = new MonitorState(this, roomId);
        new NotificationHelper(this);

        bindViews();
        // 趋势图默认显示电量；用户可随时切换到同一采样点对应的折算电费。
        ((RadioGroup) findViewById(R.id.trendMetricGroup))
                .setOnCheckedChangeListener((group, checkedId) -> {
            boolean showAmount = checkedId == R.id.amountTrendRadio;
            trendView.setShowAmount(showAmount);
            balanceTrendAxisView.setShowAmount(showAmount);
            if (balanceTrendContainer.getVisibility() == View.VISIBLE) {
                updateBalanceTrendTitle();
            }
        });
        ((RadioGroup) findViewById(R.id.chartModeGroup))
                .setOnCheckedChangeListener((group, checkedId) ->
                        showDailyUsageChart(checkedId == R.id.dailyUsageRadio)
                );
        ((RadioGroup) findViewById(R.id.dailyUsageRangeGroup))
                .setOnCheckedChangeListener((group, checkedId) -> {
                    dailyUsageDays = checkedId == R.id.dailyUsage60Radio ? 60 : 30;
                    if (dailyUsageContainer.getVisibility() == View.VISIBLE) {
                        renderDailyUsageChart();
                    }
                });
        dailyUsageBarView.setOnBarSelectedListener(this::showDailyUsageDetail);
        trendView.setOnTrendPointSelectedListener(this::showTrendDetail);
        populate(repository.load(roomId));
        refreshStatus();
        refreshHistory();
        refreshMonitorStatus(repository.load(roomId));
        if (savedRoom && repository.isConfigured(roomId)) {
            // 先完成本地页面渲染，再异步补充最近 30 天公共采样。服务器故障不会进入
            // 当前页面的错误状态，也不会推迟下面的实时余额查询。
            syncCloudHistoryInBackground(repository.load(roomId));
        }

        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        queryButton.setOnClickListener(view -> queryNow(false));
        findViewById(R.id.openUsageStatisticsButton).setOnClickListener(view ->
                startActivity(UsageStatisticsActivity.createIntent(this, roomId))
        );
        rechargeRecordsButton.setOnClickListener(view ->
                startActivity(RechargeRecordsActivity.createIntent(this, roomId))
        );
        onlineRechargeButton.setOnClickListener(view -> startOnlineRecharge());
        saveAliasButton.setOnClickListener(view -> saveAliasOnly());
        reminderSettingsHeaderButton.setOnClickListener(view -> toggleSettingsSection(
                reminderSettingsContent, reminderSettingsHeaderButton, "提醒设置"
        ));
        saveButton.setOnClickListener(view -> saveAndSchedule());
        cancelMonitoringButton.setOnClickListener(view -> confirmCancelMonitoring());
        deleteButton.setOnClickListener(view -> confirmDelete());

        // 已保存房间进入详情后自动刷新一次；新增房间尚无有效 roomCode，不自动请求。
        if (savedRoom && repository.isConfigured(roomId)) {
            automaticQueryStarted = true;
            queryNow(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (appliedThemeState != AppThemeManager.state(this)) {
            recreate();
            return;
        }
        if (repository == null || !savedRoom || !repository.isConfigured(roomId)) return;
        AppConfig config = repository.load(roomId);
        if (repository.isMonitoringEnabled(roomId)) {
            Scheduler.scheduleAll(this, roomId, config.checkTimes);
        }
        updateScheduleText(config);
        updateMonitoringButtons();
        // 从充值记录页面返回后立即重算摘要，不要求用户重新进入房间。
        refreshHistory();
        if (automaticQueryStarted) refreshMonitorStatus(config);
        // 后台通知可能因权限关闭而没有显示，因此数据库结果才是最终凭据。先原子领取并
        // 补显已经完成的结果；只有没有待展示结果时，才恢复尚未结束的到账轮询。
        if (!showPendingRechargeResultNotice()) {
            resumePendingRechargeVerification();
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // Handler 只属于当前 Activity；移除后数据库中的 pending attempt 仍保留，
        // 用户再次进入该房间会从 onResume 恢复，不会因旋转或进程回收丢失。
        rechargeVerificationGeneration++;
        rechargeVerificationHandler.removeCallbacksAndMessages(null);
        if (rechargeVerificationDialog != null) {
            rechargeVerificationDialog.dismiss();
            rechargeVerificationDialog = null;
        }
        // 结果只有在用户按下按钮或主动关闭弹窗后才算“已看过”。旋转、主题重建等
        // 生命周期销毁不应吞掉提示，因此先把未确认的数据库 claim 释放给新 Activity。
        releaseClaimedRechargeResultNotice();
        executor.shutdownNow();
        if (historyStore != null) historyStore.close();
        super.onDestroy();
    }

    private void bindViews() {
        detailTitle = findViewById(R.id.detailTitle);
        balanceSurplusText = findViewById(R.id.balanceSurplusText);
        balanceAmountText = findViewById(R.id.balanceAmountText);
        balanceUpdatedText = findViewById(R.id.balanceUpdatedText);
        balanceStateText = findViewById(R.id.balanceStateText);
        monitorStatusText = findViewById(R.id.monitorStatusText);
        scheduleText = findViewById(R.id.scheduleText);
        trendTitleText = findViewById(R.id.trendTitleText);
        historySummaryText = findViewById(R.id.historySummaryText);
        dailyUsageDetailText = findViewById(R.id.dailyUsageDetailText);
        trendView = findViewById(R.id.trendView);
        balanceTrendAxisView = findViewById(R.id.balanceTrendAxisView);
        dailyUsageBarView = findViewById(R.id.dailyUsageBarView);
        dailyUsageAxisView = findViewById(R.id.dailyUsageAxisView);
        balanceTrendScrollView = findViewById(R.id.balanceTrendScrollView);
        dailyUsageScrollView = findViewById(R.id.dailyUsageScrollView);
        balanceTrendContainer = findViewById(R.id.balanceTrendContainer);
        dailyUsageContainer = findViewById(R.id.dailyUsageContainer);
        aliasInput = findViewById(R.id.aliasInput);
        thresholdInput = findViewById(R.id.thresholdInput);
        recoveryInput = findViewById(R.id.recoveryInput);
        repeatMinutesInput = findViewById(R.id.repeatMinutesInput);
        amountRadio = findViewById(R.id.amountRadio);
        queryButton = findViewById(R.id.queryButton);
        saveButton = findViewById(R.id.saveButton);
        cancelMonitoringButton = findViewById(R.id.cancelMonitoringButton);
        deleteButton = findViewById(R.id.deleteButton);
        rechargeRecordsButton = findViewById(R.id.openRechargeRecordsButton);
        onlineRechargeButton = findViewById(R.id.openOnlineRechargeButton);
        saveAliasButton = findViewById(R.id.saveAliasButton);
        reminderSettingsHeaderButton = findViewById(R.id.reminderSettingsHeaderButton);
        reminderSettingsContent = findViewById(R.id.reminderSettingsContent);
        quickActionsContainer = findViewById(R.id.quickActionsContainer);
        updateMonitoringButtons();
    }

    private void populate(AppConfig config) {
        detailTitle.setText(savedRoom ? config.alias : "添加房间");
        aliasInput.setText(config.alias);
        amountRadio.setChecked("amount".equals(config.metric));
        ((RadioButton) findViewById(R.id.surplusRadio)).setChecked("surplus".equals(config.metric));
        thresholdInput.setText(number(config.threshold));
        recoveryInput.setText(number(config.recoveryThreshold));
        repeatMinutesInput.setText(number(config.repeatMinutes));
        saveAliasButton.setEnabled(savedRoom);
        // 添加流程已在首页完成房间选择，详情页只让用户维护备注和提醒规则。
        setSettingsSection(reminderSettingsContent, reminderSettingsHeaderButton, "提醒设置", !savedRoom);
        if (savedRoom) updateScheduleText(config);
    }

    /**
     * 在线充值入口只对已经保存且房间码有效的房间开放。
     *
     * <p>第一阶段仅加载校付宝规则与余额，不申请提交令牌、不创建订单。只有用户输入金额并
     * 完成第二次确认后，才进入 createOnlineRechargeOrder，从交互层减少误触下单。</p>
     */
    private void startOnlineRecharge() {
        if (rechargeBusy) return;
        if (!savedRoom || !repository.isConfigured(roomId)) {
            toast("请先保存有效房间信息");
            return;
        }
        AppConfig config = repository.load(roomId);
        try {
            config.validate();
        } catch (IllegalArgumentException exception) {
            toast("房间配置无效，请先检查并保存房间信息");
            return;
        }

        setRechargeBusy(true, "正在读取充值规则…");
        executor.execute(() -> {
            try {
                RechargeContext context = rechargeClient.loadContext(config);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!context.canBuy) {
                        setRechargeBusy(false, null);
                        new AlertDialog.Builder(this)
                                .setTitle("当前暂不可充值")
                                .setMessage("校付宝暂未允许该房间充值，请稍后再试。")
                                .setPositiveButton("知道了", null)
                                .show();
                        return;
                    }
                    showRechargeAmountDialog(config, context);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> showRechargeFailure(
                        "充值信息加载失败", exception, false
                ));
            }
        });
    }

    /**
     * 金额页仅展示服务端推荐值和一个自定义输入框。
     *
     * <p>房间、余额和规则已经在进入页面前完成校验，不重复铺陈说明文字。推荐项点击后
     * 只填入输入框，不会直接下单；用户仍需再点击“充值”，避免误触创建订单。</p>
     */
    private void showRechargeAmountDialog(
            AppConfig config, RechargeContext context
    ) {
        View content = getLayoutInflater().inflate(
                R.layout.dialog_online_recharge, null, false
        );
        EditText amountInput = content.findViewById(R.id.onlineRechargeAmountInput);
        LinearLayout suggestions = content.findViewById(
                R.id.rechargeSuggestedAmountsContainer
        );
        // 初次打开时保持推荐金额完整可见；只有用户主动点击输入框才弹出软键盘。
        content.setFocusableInTouchMode(true);
        content.requestFocus();
        amountInput.setHint(getString(
                R.string.recharge_amount_range_hint,
                ElectricityRechargeClient.formatAmount(context.minimumAmount),
                ElectricityRechargeClient.formatAmount(context.maximumAmount)
        ));
        populateRechargeSuggestions(suggestions, amountInput, context);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("电费充值")
                .setView(content)
                .setNegativeButton("取消", (ignored, which) ->
                        setRechargeBusy(false, null))
                // onShow 中覆盖点击事件，金额错误时保留对话框并在输入框下直接提示。
                .setPositiveButton("充值", null)
                .create();
        dialog.setOnCancelListener(ignored -> setRechargeBusy(false, null));
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    try {
                        String normalizedAmount = rechargeClient.normalizeAmount(
                                text(amountInput), context
                        );
                        amountInput.setError(null);
                        // 金额已完成本地范围校验；付款仍需在微信收银台最终确认。
                        dialog.setOnCancelListener(null);
                        dialog.dismiss();
                        createOnlineRechargeOrder(config, context, normalizedAmount);
                    } catch (IllegalArgumentException exception) {
                        amountInput.setError(exception.getMessage());
                        amountInput.requestFocus();
                    }
                })
        );
        dialog.show();
    }

    /**
     * 使用三列等宽推荐金额；服务端未返回推荐项时才回退到学校页面的常用金额。
     * 所有回退值仍会再次按服务端 min/max 过滤，不能绕过真实充值规则。
     */
    private void populateRechargeSuggestions(
            LinearLayout container, EditText amountInput, RechargeContext context
    ) {
        List<String> values = new ArrayList<>(context.suggestedAmounts);
        if (values.isEmpty()) {
            for (String fallback : new String[]{"10", "30", "50", "100", "200", "300"}) {
                try {
                    values.add(rechargeClient.normalizeAmount(fallback, context));
                } catch (IllegalArgumentException ignored) {
                    // 当前学校规则不允许的常用金额不显示。
                }
            }
        }
        int count = Math.min(values.size(), 6);
        for (int start = 0; start < count; start += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            if (start > 0) {
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                rowParams.topMargin = Math.round(dp(8));
                row.setLayoutParams(rowParams);
            }
            for (int index = start; index < Math.min(start + 3, count); index++) {
                String amount = values.get(index);
                Button choice = new Button(this, null, 0, R.style.Ui_Button_Secondary);
                choice.setText(getString(R.string.recharge_suggestion_format, amount));
                choice.setOnClickListener(view -> {
                    amountInput.setText(amount);
                    amountInput.setSelection(amount.length());
                    amountInput.setError(null);
                });
                LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1
                );
                if (index > start) choiceParams.leftMargin = Math.round(dp(8));
                row.addView(choice, choiceParams);
            }
            container.addView(row);
        }
    }

    /**
     * 申请一次性令牌并创建订单。此方法由单线程 executor 串行执行且没有重试循环；
     * 如果请求已到达服务器但响应在途中丢失，自动重试可能制造重复订单。
     */
    private void createOnlineRechargeOrder(
            AppConfig config, RechargeContext context, String amount
    ) {
        setRechargeBusy(true, "正在创建充值订单…");
        executor.execute(() -> {
            try {
                RechargeCheckout checkout = rechargeClient.createCheckout(
                        config, context, amount
                );
                // 收银台 URL 已经生成后立即把本地尝试写入数据库。只有持久化成功才打开微信，
                // 这样支付期间即使 App 进程被系统回收，也能用 payNo 继续查询官方订单。
                RechargeAttempt attempt = historyStore.createRechargeAttempt(
                        roomId, amount, checkout.paymentNo
                );
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    openCheckoutWithWechatBridge(
                            checkout.checkoutUrl, attempt.attemptId
                    );
                });
            } catch (Exception exception) {
                runOnUiThread(() -> showRechargeFailure(
                        "未能打开校付宝收银台", exception, true
                ));
            }
        });
    }

    /**
     * 默认进入受限支付过渡页，由过渡页拦截官方 H5 生成的微信支付指令并显式唤起微信。
     * 这里仍使用 Activity 结果通知房间页“支付流程已经离开”，但结果不代表付款成功。
     */
    @SuppressWarnings("deprecation")
    private void openCheckoutWithWechatBridge(
            String checkoutUrl, String attemptId
    ) {
        try {
            // 先写 launched，再调用系统；若启动失败会立即删除 attempt。这样进程恢复时
            // 未真正展示过支付页的订单绝不会进入自动到账检测。
            historyStore.markRechargeAttemptLaunched(attemptId);
            startActivityForResult(
                    RechargePaymentActivity.createIntent(
                            this, checkoutUrl, attemptId
                    ),
                    REQUEST_RECHARGE_PAYMENT
            );
            setRechargeBusy(false, null);
        } catch (RuntimeException exception) {
            // 支付页没有真正打开，用户不可能完成该次付款；删除本地尝试，避免随后误检测。
            historyStore.deleteRechargeAttempt(attemptId);
            showRechargeFailure("无法打开支付页面", exception, true);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_RECHARGE_PAYMENT) {
            String attemptId = RechargePaymentActivity.resultAttemptId(data);
            RechargeAttempt attempt = historyStore.loadRechargeAttempt(attemptId);
            if (resultCode == RESULT_OK
                    && attempt != null
                    && roomId.equals(attempt.roomId)
                    && attempt.launchedAt > 0) {
                // RESULT_OK 仅表示确实从微信返回，绝不等于支付成功；此处只启动
                // 校付宝官方订单状态查询。
                historyStore.markRechargeAttemptReturned(attempt.attemptId);
                startRechargeVerification(attempt);
            } else if (attempt != null
                    && roomId.equals(attempt.roomId)
                    && !RechargeAttempt.STATUS_CONFIRMED.equals(attempt.status)) {
                // 用户在进入微信前关闭、WebView 加载失败或微信不可用：没有可验证付款，
                // 删除本地尝试，不进行无意义的两分钟轮询。
                historyStore.deleteRechargeAttempt(attempt.attemptId);
            }
        }
    }

    /** 页面或进程恢复后只自动继续 pending；已提示过的 unconfirmed 不会反复打扰用户。 */
    private void resumePendingRechargeVerification() {
        if (rechargeVerificationRunning || historyStore == null || !savedRoom) return;
        RechargeAttempt attempt = historyStore.loadLatestVerifiableRechargeAttempt(
                roomId, RECHARGE_ATTEMPT_MAX_AGE
        );
        if (attempt != null
                && RechargeAttempt.STATUS_PENDING.equals(attempt.status)) {
            // 只有支付页已经写入 launched_at 和 returned_at 的尝试会来到这里；
            // 订单仅创建但从未展示、或用户进入微信前取消的尝试不会被自动检测。
            startRechargeVerification(attempt);
        }
    }

    /**
     * 逐步轮询校付宝官方订单接口。对话框可选择稍后确认；Activity 销毁时只停止本轮任务，
     * 数据库 attempt 仍为 pending，下一次打开房间会自动恢复。
     */
    private void startRechargeVerification(RechargeAttempt attempt) {
        if (attempt == null || rechargeVerificationRunning
                || isFinishing() || isDestroyed()) return;
        // 数据库代次和下面的 UI 代次用途不同：前者阻止旧线程落库，后者阻止旧回调更新
        // 已销毁的界面。只有 launched_at 与 returned_at 齐全的尝试才能取得有效代次。
        long databaseGeneration = historyStore.beginRechargeVerification(
                attempt.attemptId
        );
        if (databaseGeneration < 0) {
            showPendingRechargeResultNotice();
            return;
        }
        rechargeVerificationRunning = true;
        rechargeVerificationSuccessfulQueries = 0;
        int uiGeneration = ++rechargeVerificationGeneration;
        long startedAt = SystemClock.elapsedRealtime();
        setRechargeBusy(true, "正在确认到账…");

        rechargeVerificationDialog = new AlertDialog.Builder(this)
                .setTitle("正在确认到账")
                .setMessage("正在查询校付宝官方订单…")
                .setCancelable(true)
                .setNegativeButton("稍后确认", (ignored, which) ->
                        pauseRechargeVerification(
                                attempt, databaseGeneration, true
                        ))
                .create();
        rechargeVerificationDialog.setOnCancelListener(ignored ->
                pauseRechargeVerification(attempt, databaseGeneration, true));
        rechargeVerificationDialog.show();
        scheduleRechargeVerification(
                attempt, 0, uiGeneration, databaseGeneration, startedAt
        );
    }

    private void scheduleRechargeVerification(
            RechargeAttempt attempt,
            int index,
            int uiGeneration,
            long databaseGeneration,
            long startedAt
    ) {
        // 每个延迟值都是相对本轮开始时间的绝对时刻，而不是“上一次请求结束后再等多久”。
        // 网络请求即使很慢，也不会把原定约两分钟的检测拖成数分钟的串行等待。
        long targetAt = startedAt + RECHARGE_VERIFY_DELAYS[index];
        long delayMillis = Math.max(0, targetAt - SystemClock.elapsedRealtime());
        rechargeVerificationHandler.postDelayed(() -> {
            if (!isRechargeVerificationActive(uiGeneration)) return;
            if (rechargeVerificationDialog != null) {
                rechargeVerificationDialog.setMessage(
                        "正在确认到账（" + (index + 1)
                                + "/" + RECHARGE_VERIFY_DELAYS.length + "）…"
                );
            }
            executor.execute(() -> runRechargeVerificationQuery(
                    attempt,
                    index,
                    uiGeneration,
                    databaseGeneration,
                    startedAt
            ));
        }, delayMillis);
    }

    private void runRechargeVerificationQuery(
            RechargeAttempt attempt,
            int index,
            int uiGeneration,
            long databaseGeneration,
            long startedAt
    ) {
        long elapsedBeforeQuery = SystemClock.elapsedRealtime() - startedAt;
        boolean isFinalQuery = index == RECHARGE_VERIFY_DELAYS.length - 1;
        if ((!isFinalQuery && elapsedBeforeQuery >= RECHARGE_VERIFY_DEADLINE)
                || elapsedBeforeQuery
                > RECHARGE_VERIFY_DEADLINE + RECHARGE_FINAL_QUERY_START_GRACE) {
            // 最后一个时间点如果因线程排队严重错过，不再额外发起最长几十秒的网络请求。
            // 直接回到主线程结束本轮，保证“约两分钟”不会退化成无上限等待。
            runOnUiThread(() -> {
                if (isRechargeVerificationActive(uiGeneration)) {
                    finishRechargeVerificationUnconfirmed(
                            attempt, databaseGeneration
                    );
                }
            });
            return;
        }
        Reading reading = null;
        Exception error = null;
        boolean confirmed = false;
        boolean officiallyFailed = false;
        try {
            AppConfig config = repository.load(roomId);
            RechargeOrderStatus orderStatus = rechargeClient.queryOrderStatus(
                    config, attempt
            );
            rechargeVerificationSuccessfulQueries++;
            if (orderStatus.state == RechargeOrderStatus.SUCCESS) {
                long rechargeId = historyStore.confirmRechargeAttempt(
                        attempt.attemptId,
                        databaseGeneration,
                        orderStatus.paidAt
                );
                confirmed = rechargeId >= 0;
                if (confirmed) {
                    // 官方订单成功和自动充值记录已经在同一事务内提交。随后读取余额只为
                    // 刷新页面、图表和低余额监测，失败时绝不能推翻官方成功终态。
                    try {
                        reading = new ElectricityClient().query(config);
                        historyStore.record(
                                roomId, reading, "recharge-official-confirmed"
                        );
                        monitorState.recordSuccess(reading);
                        if (!monitorState.isBelowRecoveryThreshold(config, reading)) {
                            monitorState.updateRecovery(config, reading);
                            Scheduler.cancelRepeat(this, roomId);
                        }
                    } catch (Exception ignored) {
                        // 网络失败、登录态变化或本地附加写入失败都只影响页面刷新；
                        // 下次正常查询会补齐余额历史，官方订单成功结论保持不变。
                    }
                    if (isFinishing() || isDestroyed()) {
                        // Activity 已销毁时主线程回调不会再展示结果，先尝试系统通知；
                        // 数据库 result_notice 仍保持未领取，通知权限关闭也能在下次进房间补显。
                        new NotificationHelper(getApplicationContext()).rechargeConfirmed(
                                roomId,
                                config.alias,
                                formatRechargeCents(attempt.requestedCents)
                        );
                    }
                }
            } else if (orderStatus.state == RechargeOrderStatus.FAILED) {
                officiallyFailed = historyStore.markRechargeAttemptFailed(
                        attempt.attemptId, databaseGeneration
                );
                if (officiallyFailed && (isFinishing() || isDestroyed())) {
                    // 页面已经销毁时主线程回调会被生命周期保护丢弃，改用系统通知；
                    // 数据库结果仍未领取，通知权限关闭也能在下次进入房间时补显。
                    new NotificationHelper(getApplicationContext()).rechargeFailed(
                            roomId, config.alias
                    );
                }
            }
        } catch (Exception exception) {
            // 一旦 confirmRechargeAttempt 已经事务提交，后续附加刷新异常不能推翻成功终态。
            if (!confirmed) error = exception;
        }

        Reading result = reading;
        Exception failure = error;
        boolean credited = confirmed;
        boolean failed = officiallyFailed;
        runOnUiThread(() -> handleRechargeVerificationResult(
                attempt,
                index,
                uiGeneration,
                databaseGeneration,
                startedAt,
                result,
                failure,
                credited,
                failed
        ));
    }

    private void handleRechargeVerificationResult(
            RechargeAttempt attempt,
            int index,
            int uiGeneration,
            long databaseGeneration,
            long startedAt,
            Reading reading,
            Exception error,
            boolean credited,
            boolean officiallyFailed
    ) {
        if (!isRechargeVerificationActive(uiGeneration)) return;
        if (credited) {
            finishRechargeVerificationSuccess(attempt, reading);
            return;
        }
        if (officiallyFailed) {
            finishRechargeVerificationFailed(attempt);
            return;
        }
        if (error instanceof AuthExpiredException) {
            // 登录态失效不会靠重试自行恢复，立即结束而不是让用户无意义等待完整两分钟。
            finishRechargeVerificationUnconfirmed(
                    attempt, databaseGeneration, error
            );
            return;
        }
        int next = index + 1;
        long elapsed = SystemClock.elapsedRealtime() - startedAt;
        if (next >= RECHARGE_VERIFY_DELAYS.length
                || elapsed >= RECHARGE_VERIFY_DEADLINE) {
            finishRechargeVerificationUnconfirmed(
                    attempt, databaseGeneration
            );
            return;
        }
        // 慢网下跳过已经错过的 3/8/15/30 秒槽位，绝不把它们连续补跑。
        // 例如首查 35 秒才返回时，下一次直接对齐 60 秒，而不是立刻再查四次。
        int lastIndex = RECHARGE_VERIFY_DELAYS.length - 1;
        while (next < lastIndex && RECHARGE_VERIFY_DELAYS[next] <= elapsed) {
            next++;
        }
        scheduleRechargeVerification(
                attempt,
                next,
                uiGeneration,
                databaseGeneration,
                startedAt
        );
    }

    private boolean isRechargeVerificationActive(int generation) {
        return rechargeVerificationRunning
                && rechargeVerificationGeneration == generation
                && !isFinishing() && !isDestroyed();
    }

    private void finishRechargeVerificationSuccess(
            RechargeAttempt attempt, Reading reading
    ) {
        stopRechargeVerificationUi();
        if (reading != null) {
            showReading(reading);
            refreshMonitorStatus(repository.load(roomId));
        }
        refreshHistory();
        if (!activityResumed) {
            new NotificationHelper(this).rechargeConfirmed(
                    roomId,
                    repository.load(roomId).alias,
                    formatRechargeCents(attempt.requestedCents)
            );
            return;
        }
        RechargeAttempt notice = historyStore.claimRechargeNotice(
                attempt.attemptId
        );
        if (notice != null) {
            showRechargeResultNotice(notice, reading, true);
        }
    }

    /**
     * 官方详情明确给出失败终态时立即结束轮询。该状态与“记录尚未同步”严格区分，
     * 因此不会因为列表暂时找不到订单就过早提示付款失败。
     */
    private void finishRechargeVerificationFailed(RechargeAttempt attempt) {
        stopRechargeVerificationUi();
        if (!activityResumed) {
            new NotificationHelper(this).rechargeFailed(
                    roomId, repository.load(roomId).alias
            );
            return;
        }
        RechargeAttempt notice = historyStore.claimRechargeNotice(
                attempt.attemptId
        );
        if (notice != null) {
            showRechargeResultNotice(notice, null, true);
        }
    }

    private void finishRechargeVerificationUnconfirmed(
            RechargeAttempt attempt, long databaseGeneration
    ) {
        finishRechargeVerificationUnconfirmed(
                attempt, databaseGeneration, null
        );
    }

    private void finishRechargeVerificationUnconfirmed(
            RechargeAttempt attempt,
            long databaseGeneration,
            Exception terminalFailure
    ) {
        boolean marked = historyStore.markRechargeAttemptUnconfirmed(
                attempt.attemptId, databaseGeneration
        );
        boolean queried = terminalFailure == null
                && rechargeVerificationSuccessfulQueries > 0;
        stopRechargeVerificationUi();
        if (!marked) {
            // 到账事务与超时恰好竞争时，以数据库终态为准；绝不能在已自动记账后再提示失败。
            if (activityResumed) {
                showPendingRechargeResultNotice();
            } else {
                RechargeAttempt current = historyStore.loadRechargeAttempt(
                        attempt.attemptId
                );
                if (current != null
                        && RechargeAttempt.STATUS_CONFIRMED.equals(current.status)) {
                    new NotificationHelper(this).rechargeConfirmed(
                            roomId,
                            repository.load(roomId).alias,
                            formatRechargeCents(current.requestedCents)
                    );
                }
            }
            return;
        }
        if (!activityResumed) {
            if (terminalFailure instanceof AuthExpiredException) {
                new NotificationHelper(this).authExpired(
                        roomId, repository.load(roomId).alias
                );
            } else {
                new NotificationHelper(this).rechargeUnconfirmed(
                        roomId, repository.load(roomId).alias
                );
            }
            return;
        }
        RechargeAttempt notice = historyStore.claimRechargeNotice(
                attempt.attemptId
        );
        if (notice != null) {
            showRechargeResultNotice(notice, null, queried);
            if (terminalFailure instanceof AuthExpiredException) {
                toast("校付宝登录态已失效，暂时无法自动确认到账");
            }
        }
    }

    private void pauseRechargeVerification(
            RechargeAttempt attempt, long databaseGeneration, boolean notify
    ) {
        if (!rechargeVerificationRunning) return;
        // 先失效本地回调，再用数据库 CAS 与正在返回的网络线程竞争。若到账事务先完成，
        // mark 会失败并改为展示成功结果；若暂停先完成，旧线程随后无法再自动记账。
        stopRechargeVerificationUi();
        boolean paused = historyStore.markRechargeAttemptUnconfirmed(
                attempt.attemptId, databaseGeneration
        );
        if (paused) {
            // 保留未展示结果；用户下次回到房间时会看到“再次检测/手动记录”入口，
            // “稍后确认”不会变成没有恢复入口的永久停止。
            if (notify) toast("已暂停到账检测，下次进入房间可再次检测");
        } else {
            showPendingRechargeResultNotice();
        }
    }

    /**
     * 原子领取并展示一条持久化结果。返回 true 表示本次 onResume 已处理终态，
     * 调用方不应同时恢复 pending 轮询。
     */
    private boolean showPendingRechargeResultNotice() {
        if (historyStore == null
                || !savedRoom
                || !activityResumed
                || isFinishing()
                || isDestroyed()) {
            return false;
        }
        RechargeAttempt notice = historyStore.claimLatestUnshownRechargeNotice(
                roomId
        );
        if (notice == null) return false;
        if (rechargeVerificationRunning) stopRechargeVerificationUi();
        // 进程恢复时数据库没有保存“成功查询次数”，使用覆盖网络失败、订单同步延迟和
        // 未完成支付三种可能的保守文案，不能把“暂未出现记录”武断说成支付失败。
        return showRechargeResultNotice(notice, null, null);
    }

    /**
     * 成功、失败与未确认都由同一个入口展示，状态不仅依赖颜色或通知栏。
     * 未确认不能等同于“微信支付失败”：订单同步延迟或网络失败都会造成暂时查不到终态。
     */
    private boolean showRechargeResultNotice(
            RechargeAttempt attempt, Reading reading, Boolean queried
    ) {
        if (isFinishing() || isDestroyed()) {
            historyStore.releaseRechargeNotice(attempt);
            return false;
        }
        if (rechargeResultDialog != null) {
            // 当前页面已有结果弹窗时不覆盖它，新领取的结果退回数据库等待下次展示。
            historyStore.releaseRechargeNotice(attempt);
            return false;
        }

        claimedRechargeResultNotice = attempt;
        rechargeResultAcknowledged = false;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        if (RechargeAttempt.STATUS_CONFIRMED.equals(attempt.resultNotice)) {
            refreshHistory();
            String message = "校付宝官方订单已确认支付成功，"
                    + formatRechargeCents(attempt.requestedCents)
                    + " 元已自动加入充值记录。";
            if (reading != null) {
                message += "\n当前余额 "
                        + String.format(Locale.CHINA, "%.2f 元", reading.amount);
            }
            builder
                    .setTitle("充值成功")
                    .setMessage(message)
                    .setPositiveButton("完成", (ignored, which) ->
                            acknowledgeRechargeResultNotice());
        } else if (RechargeAttempt.STATUS_FAILED.equals(attempt.resultNotice)) {
            builder
                    .setTitle("充值未成功")
                    .setMessage("校付宝官方订单显示支付未成功，本次没有写入充值记录。")
                    .setNegativeButton("关闭", (ignored, which) ->
                            acknowledgeRechargeResultNotice())
                    .setPositiveButton("重新充值", (ignored, which) -> {
                        acknowledgeRechargeResultNotice();
                        rechargeVerificationHandler.post(this::startOnlineRecharge);
                    });
        } else if (RechargeAttempt.STATUS_UNCONFIRMED.equals(attempt.resultNotice)) {
            String title;
            String message;
            if (queried == null) {
                title = "暂时无法确认到账";
                message = "校付宝官方订单暂未返回支付成功终态。可能未完成支付、"
                        + "订单记录尚未同步，或检测期间网络不可用。本次没有自动写入充值记录。";
            } else if (queried) {
                title = "订单仍在确认中";
                message = "校付宝官方订单仍未显示支付成功，可能未完成支付，"
                        + "也可能订单记录尚未同步。本次没有自动写入充值记录。";
            } else {
                title = "暂时无法确认到账";
                message = "检测期间未能成功读取校付宝官方订单，暂时无法判断付款结果。"
                        + "本次没有自动写入充值记录。";
            }
            builder
                    .setTitle(title)
                    .setMessage(message)
                    .setNegativeButton("关闭", (ignored, which) ->
                            acknowledgeRechargeResultNotice())
                    .setNeutralButton("手动记录", (ignored, which) -> {
                        acknowledgeRechargeResultNotice();
                        startActivity(RechargeRecordsActivity.createIntent(this, roomId));
                    })
                    .setPositiveButton("再次检测", (ignored, which) -> {
                        acknowledgeRechargeResultNotice();
                        // 等当前结果对话框完成 dismiss 后再展示检测对话框，避免窗口重叠。
                        rechargeVerificationHandler.post(() ->
                                startRechargeVerification(attempt));
                    });
        } else {
            historyStore.releaseRechargeNotice(attempt);
            claimedRechargeResultNotice = null;
            return false;
        }

        AlertDialog dialog = builder.create();
        rechargeResultDialog = dialog;
        dialog.setOnCancelListener(ignored -> acknowledgeRechargeResultNotice());
        dialog.setOnDismissListener(ignored -> {
            if (!rechargeResultAcknowledged
                    && claimedRechargeResultNotice == attempt) {
                historyStore.releaseRechargeNotice(attempt);
            }
            if (rechargeResultDialog == dialog) {
                rechargeResultDialog = null;
                claimedRechargeResultNotice = null;
                rechargeResultAcknowledged = false;
            }
        });
        try {
            dialog.show();
            return true;
        } catch (RuntimeException exception) {
            // Window token 在极窄生命周期窗口失效时不让 App 闪退，并退回 notice。
            dialog.setOnDismissListener(null);
            historyStore.releaseRechargeNotice(attempt);
            rechargeResultDialog = null;
            claimedRechargeResultNotice = null;
            rechargeResultAcknowledged = false;
            return false;
        }
    }

    /** 用户明确关闭或选择后才清理通知栏；仅仅创建了对话框不代表用户已经看见。 */
    private void acknowledgeRechargeResultNotice() {
        if (claimedRechargeResultNotice == null) return;
        rechargeResultAcknowledged = true;
        new NotificationHelper(this).clearRechargeResultNotifications(roomId);
    }

    /** 生命周期重建前释放未确认 claim，新 Activity 的 onResume 会重新领取并展示。 */
    private void releaseClaimedRechargeResultNotice() {
        if (claimedRechargeResultNotice != null && !rechargeResultAcknowledged) {
            historyStore.releaseRechargeNotice(claimedRechargeResultNotice);
        }
        if (rechargeResultDialog != null) {
            rechargeResultDialog.setOnCancelListener(null);
            rechargeResultDialog.setOnDismissListener(null);
            rechargeResultDialog.dismiss();
        }
        rechargeResultDialog = null;
        claimedRechargeResultNotice = null;
        rechargeResultAcknowledged = false;
    }

    private void stopRechargeVerificationUi() {
        rechargeVerificationRunning = false;
        rechargeVerificationGeneration++;
        rechargeVerificationHandler.removeCallbacksAndMessages(null);
        if (rechargeVerificationDialog != null) {
            rechargeVerificationDialog.setOnCancelListener(null);
            rechargeVerificationDialog.dismiss();
            rechargeVerificationDialog = null;
        }
        setRechargeBusy(false, null);
    }

    private String formatRechargeCents(long cents) {
        return ElectricityRechargeClient.formatAmount(
                BigDecimal.valueOf(cents, 2)
        );
    }

    private void showRechargeFailure(
            String title, Exception exception, boolean mayHaveCreatedOrder
    ) {
        setRechargeBusy(false, null);
        if (isFinishing() || isDestroyed()) return;
        String message = safeMessage(exception);
        if (mayHaveCreatedOrder) {
            message += "\n\n如果错误发生在确认下单之后，请不要连续重试，"
                    + "先稍候并检查该房间是否已有处理中订单。";
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? "未知错误" : exception.getMessage();
    }

    private void setRechargeBusy(boolean busy, String busyLabel) {
        rechargeBusy = busy;
        if (onlineRechargeButton == null) return;
        onlineRechargeButton.setEnabled(!busy);
        onlineRechargeButton.setText(
                busy
                        ? (busyLabel == null ? "正在处理…" : busyLabel)
                        : getString(R.string.online_recharge_button)
        );
    }

    /** 折叠只改变可见性，不重新 inflate 或清空输入框，所以用户尚未保存的内容会完整保留。 */
    private void toggleSettingsSection(LinearLayout content, Button header, String title) {
        setSettingsSection(content, header, title, content.getVisibility() != View.VISIBLE);
    }

    private void setSettingsSection(
            LinearLayout content, Button header, String title, boolean expanded
    ) {
        header.setText(title + (expanded ? "  ▴" : "  ▾"));
        header.setContentDescription(title + (expanded ? "，已展开，点击折叠" : "，已折叠，点击展开"));
        if (expanded && content.getVisibility() != View.VISIBLE) {
            content.setAlpha(0f);
            content.setVisibility(View.VISIBLE);
            content.animate().alpha(1f).setDuration(180L).start();
        } else if (!expanded) {
            content.animate().cancel();
            content.setVisibility(View.GONE);
            content.setAlpha(1f);
        }
    }

    /** 只保存别名，不触碰页面中尚未保存的提醒规则，也不会启用、暂停或重排后台任务。 */
    private void saveAliasOnly() {
        try {
            String alias = repository.updateAlias(roomId, text(aliasInput));
            aliasInput.setText(alias);
            detailTitle.setText(alias);
            toast("房间别名已保存");
        } catch (IllegalArgumentException exception) {
            toast(exception.getMessage());
        }
    }

    private AppConfig readForm() {
        thresholdInput.setError(null);
        recoveryInput.setError(null);
        repeatMinutesInput.setError(null);
        try {
            if (!savedRoom || !repository.contains(roomId)) {
                throw new IllegalArgumentException("请从首页重新添加房间");
            }
            AppConfig savedConfig = repository.load(roomId);
            double threshold = parseNumber(thresholdInput, "请输入提醒阈值");
            double recovery = parseNumber(recoveryInput, "请输入恢复阈值");
            double repeatMinutes = parseNumber(repeatMinutesInput, "请输入复查间隔");
            if (recovery <= threshold) {
                recoveryInput.setError("恢复阈值必须大于提醒阈值");
                throw new IllegalArgumentException("请检查恢复阈值");
            }
            if (repeatMinutes < 1) {
                repeatMinutesInput.setError("复查间隔至少为 1 分钟");
                throw new IllegalArgumentException("请检查复查间隔");
            }
            // 0.16.0 的调度固定为每个整点。旧时间列表仅继续随配置保存，目的是升级时能
            // 精确取消系统里尚未触发的旧闹钟；用户界面不再要求维护这些时间点。
            List<DailyCheckTime> legacyTimes = savedRoom && repository.contains(roomId)
                    ? repository.load(roomId).checkTimes
                    : Collections.singletonList(new DailyCheckTime(9, 0));
            AppConfig config = new AppConfig(
                    text(aliasInput),
                    // 房间码只在首页添加窗口确定。详情页不再提供修改入口，防止用户
                    // 误改代码后让已有历史、充值记录和监测规则指向另一个房间。
                    savedConfig.roomCode,
                    amountRadio.isChecked() ? "amount" : "surplus",
                    threshold,
                    recovery,
                    repeatMinutes,
                    legacyTimes
            );
            config.validate();
            return config;
        } catch (NumberFormatException exception) {
            setSettingsSection(
                    reminderSettingsContent, reminderSettingsHeaderButton, "提醒设置", true
            );
            throw new IllegalArgumentException("阈值和重复间隔必须填写数字");
        } catch (IllegalArgumentException exception) {
            setSettingsSection(
                    reminderSettingsContent, reminderSettingsHeaderButton, "提醒设置", true
            );
            throw exception;
        }
    }

    private double parseNumber(EditText input, String emptyMessage) {
        if (text(input).isEmpty()) {
            input.setError(emptyMessage);
            throw new IllegalArgumentException(emptyMessage);
        }
        try {
            return Double.parseDouble(text(input));
        } catch (NumberFormatException exception) {
            input.setError("请输入有效数字");
            throw exception;
        }
    }

    private void saveAndSchedule() {
        try {
            AppConfig config = readForm();
            List<DailyCheckTime> oldTimes = savedRoom && repository.contains(roomId)
                    ? repository.load(roomId).checkTimes : Collections.emptyList();
            repository.save(roomId, config);
            // scheduleAll 会取消升级前的自定义时间闹钟，并只预约下一个整点。
            Scheduler.cancelDailyAlarms(this, roomId, oldTimes);
            savedRoom = true;
            repository.setMonitoringEnabled(roomId, true);
            detailTitle.setText(config.alias);
            saveAliasButton.setEnabled(true);
            updateMonitoringButtons();
            monitorState.resetLowAlertState();
            Scheduler.cancelRepeat(this, roomId);
            Scheduler.scheduleAll(this, roomId, config.checkTimes);
            updateScheduleText(config);
            refreshMonitorStatus(config);
            // 系统权限统一由首页“设置检查”维护，详情页只负责当前房间的业务配置。
            SystemSettingsStatus settings = SystemSettingsChecker.check(this);
            toast(settings.allReady()
                    ? "房间配置已保存，每整点监测已启用"
                    : "配置已保存并启用；首页设置检查仍有 "
                    + settings.missingCount() + " 项待处理");
        } catch (IllegalArgumentException exception) {
            toast(exception.getMessage());
        }
    }

    private void queryNow(boolean automatic) {
        final AppConfig config;
        try {
            config = readForm();
            boolean wasMonitoring = savedRoom && repository.contains(roomId)
                    && repository.isMonitoringEnabled(roomId);
            List<DailyCheckTime> oldTimes = savedRoom && repository.contains(roomId)
                    ? repository.load(roomId).checkTimes : Collections.emptyList();
            // 自动查询只针对已经保存的房间；手动查询新房间时先保存，保证历史有稳定 roomId。
            repository.save(roomId, config);
            // “立即查询”也会保存表单；若监测已启用，确保下一次整点闹钟仍然存在。
            if (wasMonitoring) {
                Scheduler.cancelDailyAlarms(this, roomId, oldTimes);
                Scheduler.scheduleAll(this, roomId, config.checkTimes);
            }
            savedRoom = true;
            detailTitle.setText(config.alias);
            saveAliasButton.setEnabled(true);
            updateMonitoringButtons();
            updateScheduleText(config);
        } catch (IllegalArgumentException exception) {
            if (!automatic) toast(exception.getMessage());
            return;
        }

        setBusy(true);
        balanceUpdatedText.setText(automatic ? "正在自动更新余额……" : "正在刷新余额……");
        balanceUpdatedText.setTextColor(getColor(R.color.text_secondary));
        executor.execute(() -> {
            try {
                Reading reading = new ElectricityClient().query(config);
                monitorState.recordSuccess(reading);
                historyStore.record(roomId, reading, "manual");
                if (!monitorState.isBelowRecoveryThreshold(config, reading)) {
                    monitorState.updateRecovery(config, reading);
                    Scheduler.cancelRepeat(this, roomId);
                }
                runOnUiThread(() -> {
                    showReading(reading);
                    refreshMonitorStatus(config);
                    refreshHistory();
                    setBusy(false);
                });
                // 实时查询已经成功展示和写入本地后才启动补充同步。两条网络链路互不
                // 等待，云端超时不会改变本次查询的成功结果。
                syncCloudHistoryInBackground(config);
            } catch (AuthExpiredException exception) {
                monitorState.recordFailure(exception.getMessage());
                runOnUiThread(() -> {
                    showQueryError("登录凭据失效，请更新应用");
                    refreshMonitorStatus(config);
                    setBusy(false);
                });
            } catch (Exception exception) {
                String message = exception.getMessage() == null ? "未知错误" : exception.getMessage();
                monitorState.recordFailure(message);
                runOnUiThread(() -> {
                    showQueryError("查询失败：" + message);
                    refreshMonitorStatus(config);
                    setBusy(false);
                });
            }
        });
    }

    private void refreshStatus() {
        long timestamp = monitorState.getLastSuccessAt();
        if (timestamp <= 0) return;
        showReading(new Reading(
                monitorState.getLastSurplus(), monitorState.getLastAmount(), timestamp
        ));
    }

    private void refreshMonitorStatus(AppConfig config) {
        String status;
        String shortStatus;
        int background;
        int textColor;
        if (savedRoom && !repository.isMonitoringEnabled(roomId)) {
            status = "整点监测已暂停；手动查询、配置和历史数据仍可使用";
            shortStatus = "监测暂停";
            background = R.drawable.status_pill_neutral;
            textColor = R.color.text_secondary;
        } else if (!monitorState.getLastError().isEmpty()) {
            status = "监测异常：" + monitorState.getLastError();
            shortStatus = "查询异常";
            background = R.drawable.status_pill_danger;
            textColor = R.color.status_danger;
        } else if (monitorState.isLowAlertActive()) {
            status = "余额不足，正在按设定间隔重复查询";
            shortStatus = "余额不足";
            background = R.drawable.status_pill_warning;
            textColor = R.color.status_warning;
        } else if (monitorState.getLastSuccessAt() <= 0) {
            status = "等待首次成功查询";
            shortStatus = "等待查询";
            background = R.drawable.status_pill_neutral;
            textColor = R.color.text_secondary;
        } else {
            Reading latest = new Reading(
                    monitorState.getLastSurplus(), monitorState.getLastAmount(),
                    monitorState.getLastSuccessAt()
            );
            boolean below = monitorState.isBelowAlertThreshold(config, latest);
            status = below ? "余额低于提醒阈值，等待整点任务启动告警" : "余额正常，后台每整点监测中";
            shortStatus = below ? "余额偏低" : "余额正常";
            background = below ? R.drawable.status_pill_warning : R.drawable.status_pill_normal;
            textColor = below ? R.color.status_warning : R.color.status_normal;
        }
        monitorStatusText.setText(status);
        balanceStateText.setText(shortStatus);
        balanceStateText.setBackgroundResource(background);
        balanceStateText.setTextColor(getColor(textColor));
    }

    private void refreshHistory() {
        // 多读两天用于计算 60 天柱状图第一根柱子的“前一天 → 当天”消耗。
        latestHistoryPoints = historyStore.loadDailyPoints(roomId, 62);
        latestRechargeRecords = historyStore.loadRecharges(roomId);
        // 余额趋势加载最近 30 天整小时采样。宽时间轴由 HorizontalScrollView 承载，
        // 用户向左滑即可查看旧数据，不会把最多 720 个小时点强行压缩到一屏。
        // 向前保留一个窗口外点，才能计算可见第一段的真实耗电速率。
        List<HistoryPoint> hourlyReadings = historyStore.loadHourlyPoints(roomId, 30 * 24);
        long trendCutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1_000;
        int firstVisible = 0;
        while (firstVisible < hourlyReadings.size()
                && hourlyReadings.get(firstVisible).timestamp < trendCutoff) {
            firstVisible++;
        }
        int calculationStart = Math.max(0, firstVisible - 1);
        if (calculationStart > 0) {
            hourlyReadings = new ArrayList<>(
                    hourlyReadings.subList(calculationStart, hourlyReadings.size())
            );
        }
        List<BalanceTrendPoint> hourlyTrend = BalanceTrendCalculator.calculate(
                hourlyReadings, latestRechargeRecords
        );
        trendView.setData(hourlyTrend, latestRechargeRecords);
        balanceTrendAxisView.setData(hourlyTrend);
        // 默认从最右侧的最新记录开始查看；用户随后可自然地向左回看更早时段。
        balanceTrendScrollView.post(
                () -> balanceTrendScrollView.fullScroll(View.FOCUS_RIGHT)
        );
        if (dailyUsageContainer.getVisibility() == View.VISIBLE) renderDailyUsageChart();
        List<HistoryPoint> trendPoints = tail(latestHistoryPoints, 60);
        if (trendPoints.size() < 2) {
            historySummaryText.setText(String.format(
                    Locale.CHINA,
                    "已记录 %d 个采样日、%d 个近期小时点；整点监测运行后可查看时段速率。",
                    trendPoints.size(), hourlyTrend.size()
            ));
            return;
        }

        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate endExclusive = LocalDate.now(zoneId).plusDays(1);
        LocalDate startInclusive = endExclusive.minusDays(60);
        UsagePeriodStats stats = UsageStatisticsCalculator.calculate(
                latestHistoryPoints, latestRechargeRecords,
                startInclusive, endExclusive, zoneId
        );
        double recordedRechargeAmount = 0;
        for (RechargeRecord recharge : latestRechargeRecords) {
            if (!recharge.date.isBefore(startInclusive)
                    && recharge.date.isBefore(endExclusive)) {
                recordedRechargeAmount += recharge.amount;
            }
        }
        historySummaryText.setText(String.format(
                Locale.CHINA,
                "近 60 天（%d 个采样日）估算用电 %.2f 度 · 约 %.2f 元\n"
                        + "已登记充值 %.2f 元 · 未匹配上涨区间 %d 段\n"
                        + "余额源延迟刷新时按确认窗口均摊；尾部相同余额显示为待结算。",
                trendPoints.size(), stats.usageKwh, stats.costAmount,
                recordedRechargeAmount, stats.excludedRechargeIntervals
        ));
    }

    /** 云端有新增记录时才刷新图表；页面已销毁或无新增记录时不做任何 UI 操作。 */
    private void syncCloudHistoryInBackground(AppConfig config) {
        if (config == null || config.roomCode == null || config.roomCode.isEmpty()) return;
        CloudHistorySynchronizer.sync(this, roomId, config.roomCode, importedCount ->
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && historyStore != null) {
                        refreshHistory();
                    }
                })
        );
    }

    private void showDailyUsageChart(boolean showDaily) {
        LinearLayout showing = showDaily ? dailyUsageContainer : balanceTrendContainer;
        LinearLayout hiding = showDaily ? balanceTrendContainer : dailyUsageContainer;
        hiding.animate().cancel();
        hiding.setVisibility(View.GONE);
        showing.setAlpha(0f);
        showing.setVisibility(View.VISIBLE);
        // 仅使用短暂淡入，不改变尺寸或阻碍操作；避免分段切换时产生生硬闪烁。
        showing.animate().alpha(1f).setDuration(160L).start();
        if (showDaily) {
            renderDailyUsageChart();
        } else {
            updateBalanceTrendTitle();
            // 余额趋势默认处于隐藏状态，初次加载时容器还没有可滚动宽度；必须在用户
            // 真正切换到该图表、完成布局后再次定位最右侧，保证第一眼看到最新余额。
            balanceTrendScrollView.post(
                    () -> balanceTrendScrollView.fullScroll(View.FOCUS_RIGHT)
            );
        }
    }

    private void updateBalanceTrendTitle() {
        boolean showAmount = ((RadioButton) findViewById(R.id.amountTrendRadio)).isChecked();
        trendTitleText.setText(showAmount
                ? "余额趋势 · 最近 30 天电费"
                : "余额趋势 · 最近 30 天电量");
    }

    /** 点击趋势的某个时段后，同时给出用量、金额、每小时速率和该区间充值笔数。 */
    private void showTrendDetail(BalanceTrendPoint point) {
        TextView detail = findViewById(R.id.trendDetailText);
        ZoneId zoneId = ZoneId.systemDefault();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                "M月d日 HH:mm", Locale.CHINA
        );
        String start = formatter.format(
                Instant.ofEpochMilli(point.intervalStart).atZone(zoneId)
        );
        String end = formatter.format(
                Instant.ofEpochMilli(point.reading.timestamp).atZone(zoneId)
        );
        if (point.awaitingSourceUpdate) {
            detail.setText(start + "–" + end
                    + "：余额源尚未产生下一次变化，该时段正在等待结算，暂不显示为零用电");
            return;
        }
        if (!point.rateValid) {
            detail.setText(point.unmatchedIncrease
                    ? start + "–" + end + "：检测到未登记的余额上涨，无法可靠计算该时段速率"
                    : start + "–" + end + "：采样间隔不足，暂时无法计算速率");
            return;
        }
        String recharge = point.rechargeCount > 0
                ? String.format(
                        Locale.CHINA, " · 期间 %d 笔充值共 %.2f 元",
                        point.rechargeCount, point.rechargeAmount
                ) : "";
        String estimate = point.estimated
                ? String.format(
                        Locale.CHINA, "（根据 %.1f 小时累计变化均摊）",
                        point.estimateSpanHours
                ) : "（相邻采样直接计算）";
        detail.setText(String.format(
                Locale.CHINA,
                "%s–%s：用电 %.3f 度 · %.3f 度/小时 · 约 %.2f 元%s\n%s",
                start, end, point.usageKwh, point.rateKwhPerHour,
                point.costAmount, recharge, estimate
        ));
    }

    /**
     * 每根柱子表示“前一个采样日到该日期”的消费。复用月度统计计算器，因此充值修正、
     * 跨日分摊和未匹配上涨排除规则在折线页与月度页完全一致。
     */
    private void renderDailyUsageChart() {
        if (dailyUsageBarView == null) return;
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zoneId);
        LocalDate firstDate = today.minusDays(dailyUsageDays - 1L);
        List<DailyUsagePoint> bars = new ArrayList<>();
        for (int offset = 0; offset < dailyUsageDays; offset++) {
            LocalDate date = firstDate.plusDays(offset);
            UsagePeriodStats stats = UsageStatisticsCalculator.calculate(
                    latestHistoryPoints, latestRechargeRecords,
                    date.minusDays(1), date, zoneId
            );
            bars.add(new DailyUsagePoint(
                    date, stats.usageKwh, stats.costAmount, stats.hasData()
            ));
        }
        dailyUsageBarView.setPoints(bars);
        // 固定纵轴和可滚动柱子必须使用同一批数据，才能保证三个刻度与柱高一一对应。
        dailyUsageAxisView.setPoints(bars);
        trendTitleText.setText("每日消耗 · " + dailyUsageDays + " 天");
        dailyUsageDetailText.setText("点击柱子查看日期、当天用量和金额");
        // 初次打开和切换范围时定位到最右侧，让用户先看到最近日期。
        dailyUsageScrollView.post(() ->
                dailyUsageScrollView.fullScroll(View.FOCUS_RIGHT)
        );
    }

    private void showDailyUsageDetail(DailyUsagePoint point) {
        String date = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
                .format(point.date);
        dailyUsageDetailText.setText(point.hasData
                ? String.format(
                        Locale.CHINA, "%s：当天估算用电 %.2f 度 · 约 %.2f 元",
                        date, point.usageKwh, point.costAmount
                )
                : date + "：尚无已确认消耗，可能仍在等待余额源更新，或存在未登记上涨");
    }

    private List<HistoryPoint> tail(List<HistoryPoint> points, int maximumSize) {
        int start = Math.max(0, points.size() - maximumSize);
        return new ArrayList<>(points.subList(start, points.size()));
    }

    /** 余额拆分到三个文本层级，主数据不会再与更新时间挤在同一段文字中。 */
    private void showReading(Reading reading) {
        DateFormat format = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT, Locale.CHINA
        );
        balanceSurplusText.setText(String.format(Locale.CHINA, "%.2f 度", reading.surplus));
        balanceAmountText.setText(String.format(Locale.CHINA, "折算金额 %.2f 元", reading.amount));
        balanceUpdatedText.setText("更新于 " + format.format(new Date(reading.timestamp)));
        balanceUpdatedText.setTextColor(getColor(R.color.text_tertiary));
    }

    private void showQueryError(String message) {
        // 查询失败时保留上一次余额，只在更新时间位置显示错误，避免瞬间抹掉仍有参考价值的数据。
        balanceUpdatedText.setText(message);
        balanceUpdatedText.setTextColor(getColor(R.color.status_danger));
    }

    private void updateScheduleText(AppConfig config) {
        if (savedRoom && !repository.isMonitoringEnabled(roomId)) {
            scheduleText.setText("整点自动监测已暂停\n房间配置与历史曲线已保留。");
            return;
        }
        String precision = Scheduler.canScheduleExactAlarms(this)
                ? "精确闹钟已允许" : "未允许精确闹钟，系统可能延迟";
        scheduleText.setText(String.format(
                Locale.CHINA,
                "每天 00:00–23:00 每个整点自动查询并记录\n"
                        + "余额持续不足每 %.0f 分钟复查 · %s",
                config.repeatMinutes, precision
        ));
    }

    private void confirmCancelMonitoring() {
        new AlertDialog.Builder(this)
                .setTitle("取消整点监测")
                .setMessage("将停止该房间的整点查询和低余额重复提醒，但保留配置与历史曲线。")
                .setNegativeButton("暂不取消", null)
                .setPositiveButton("确认取消", (dialog, which) -> {
                    repository.setMonitoringEnabled(roomId, false);
                    Scheduler.cancelAllForRoom(this, roomId);
                    monitorState.resetLowAlertState();
                    AppConfig config = repository.load(roomId);
                    updateScheduleText(config);
                    refreshMonitorStatus(config);
                    updateMonitoringButtons();
                    toast("该房间的整点监测已暂停");
                })
                .show();
    }

    private void updateMonitoringButtons() {
        if (cancelMonitoringButton == null || deleteButton == null) return;
        deleteButton.setVisibility(savedRoom ? View.VISIBLE : View.GONE);
        rechargeRecordsButton.setVisibility(savedRoom ? View.VISIBLE : View.GONE);
        onlineRechargeButton.setVisibility(savedRoom ? View.VISIBLE : View.GONE);
        quickActionsContainer.setVisibility(savedRoom ? View.VISIBLE : View.GONE);
        cancelMonitoringButton.setVisibility(savedRoom ? View.VISIBLE : View.GONE);
        boolean enabled = savedRoom && repository.isMonitoringEnabled(roomId);
        cancelMonitoringButton.setEnabled(enabled);
        cancelMonitoringButton.setText(enabled ? "取消整点监测" : "整点监测已暂停");
        if (saveAliasButton != null) saveAliasButton.setEnabled(savedRoom);
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除房间")
                .setMessage("将删除该房间配置、历史曲线和后台提醒，且无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    repository.delete(roomId);
                    finish();
                })
                .show();
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

    private void setBusy(boolean busy) {
        queryButton.setEnabled(!busy);
        queryButton.setText(busy ? "刷新中…" : "↻ 刷新");
        saveButton.setEnabled(!busy);
    }

    private String text(EditText input) {
        return input.getText().toString().trim();
    }

    private String number(double value) {
        return value == Math.rint(value)
                ? Long.toString((long) value) : Double.toString(value);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
