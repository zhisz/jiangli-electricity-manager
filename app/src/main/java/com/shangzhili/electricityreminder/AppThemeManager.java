package com.shangzhili.electricityreminder;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.TypedValue;

/**
 * 管理应用视觉主题，不保存任何房间配置或业务数据。
 *
 * <p>原生 Android 主题必须在 Activity 的 {@code super.onCreate()} 之前应用，因此每个页面
 * 会保存本次创建时的模式，并在 onResume 时检查用户是否刚从设置页切换了主题。</p>
 */
public final class AppThemeManager {
    public static final int MODE_BLUE = 0;
    public static final int MODE_JADE = 1;
    public static final int MODE_PURPLE = 2;

    /** 显示模式与品牌色相互独立，因此蓝、绿、紫均可搭配浅色或深色界面。 */
    public static final int APPEARANCE_FOLLOW_SYSTEM = 0;
    public static final int APPEARANCE_LIGHT = 1;
    public static final int APPEARANCE_DARK = 2;
    private static final String PREFS_NAME = "appearance_preferences";
    private static final String KEY_THEME_MODE = "themeMode";
    private static final String KEY_APPEARANCE_MODE = "appearanceMode";

    private AppThemeManager() {}

    /**
     * 应用当前显示模式与品牌色，并返回本次页面使用的完整状态快照。
     *
     * <p>夜间资源必须在 {@code super.onCreate()} 之前确定，否则布局会先读取浅色资源，
     * 随后再切换会产生闪屏。显式浅色/深色使用 Activity 级配置覆盖；“跟随系统”不做覆盖，
     * 由 Android 在系统主题变化时按标准配置变更机制重新创建页面。</p>
     */
    public static int apply(Activity activity) {
        int themeMode = current(activity);
        if (themeMode == MODE_JADE) activity.setTheme(R.style.AppTheme_Jade);
        else if (themeMode == MODE_PURPLE) activity.setTheme(R.style.AppTheme_Purple);
        else activity.setTheme(R.style.AppTheme);
        return state(activity);
    }

    public static int current(Context context) {
        int value = preferences(context).getInt(KEY_THEME_MODE, MODE_BLUE);
        return value >= MODE_BLUE && value <= MODE_PURPLE ? value : MODE_BLUE;
    }

    public static void save(Context context, int mode) {
        if (mode < MODE_BLUE || mode > MODE_PURPLE) mode = MODE_BLUE;
        preferences(context).edit().putInt(KEY_THEME_MODE, mode).apply();
    }

    /** 老用户默认保持浅色，只有主动选择后才切换为深色或跟随系统。 */
    public static int currentAppearance(Context context) {
        int value = preferences(context).getInt(KEY_APPEARANCE_MODE, APPEARANCE_LIGHT);
        return value >= APPEARANCE_FOLLOW_SYSTEM && value <= APPEARANCE_DARK
                ? value : APPEARANCE_LIGHT;
    }

    public static void saveAppearance(Context context, int mode) {
        if (mode < APPEARANCE_FOLLOW_SYSTEM || mode > APPEARANCE_DARK) {
            mode = APPEARANCE_LIGHT;
        }
        preferences(context).edit().putInt(KEY_APPEARANCE_MODE, mode).apply();
    }

    /** 用一个整数同时比较品牌色和显示模式，供页面从设置页返回时判断是否需要重建。 */
    public static int state(Context context) {
        return current(context) * 10 + currentAppearance(context);
    }

    /**
     * 在 Activity 尚未附着基础 Context 时创建带深浅模式的 Context。
     *
     * <p>这是原生 Activity 最安全的配置切换时机：此处读取的是传入的基础 Context，
     * Activity 自己的 Resources 和 Assets 尚未创建，因此不会触发
     * “getResources() or getAssets() has already been called”启动崩溃。</p>
     */
    public static Context wrap(Context base) {
        int appearance = currentAppearance(base);
        if (appearance == APPEARANCE_FOLLOW_SYSTEM) return base;

        Configuration override = new Configuration(base.getResources().getConfiguration());
        int nightMode = appearance == APPEARANCE_DARK
                ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        override.uiMode = (override.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightMode;
        return base.createConfigurationContext(override);
    }

    /** 自绘图表无法直接在 Canvas 中使用 ?attr，因此通过此方法解析当前主题颜色。 */
    public static int color(Context context, int attribute) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attribute, value, true)) {
            throw new IllegalStateException("主题缺少颜色属性：" + attribute);
        }
        return value.data;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE
        );
    }

}
