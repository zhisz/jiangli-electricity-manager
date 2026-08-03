package com.shangzhili.electricityreminder;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.TypedValue;

import androidx.appcompat.app.AppCompatDelegate;

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
        // 新安装默认使用“静雅紫”。已经主动选择过主题的老用户仍读取原值，
        // 这里不做强制迁移，避免升级后突然覆盖用户的个人偏好。
        int value = preferences(context).getInt(KEY_THEME_MODE, MODE_PURPLE);
        return value >= MODE_BLUE && value <= MODE_PURPLE ? value : MODE_PURPLE;
    }

    public static void save(Context context, int mode) {
        if (mode < MODE_BLUE || mode > MODE_PURPLE) mode = MODE_PURPLE;
        preferences(context).edit().putInt(KEY_THEME_MODE, mode).commit();
    }

    /** 新安装默认深色；已经保存过显示模式的用户继续保留原选择。 */
    public static int currentAppearance(Context context) {
        int value = preferences(context).getInt(KEY_APPEARANCE_MODE, APPEARANCE_DARK);
        return value >= APPEARANCE_FOLLOW_SYSTEM && value <= APPEARANCE_DARK
                ? value : APPEARANCE_DARK;
    }

    public static void saveAppearance(Context context, int mode) {
        if (mode < APPEARANCE_FOLLOW_SYSTEM || mode > APPEARANCE_DARK) {
            mode = APPEARANCE_DARK;
        }
        /*
         * MaterialAlertDialogBuilder 内部由 AppCompatDelegate 解析 DayNight。旧实现只改
         * Activity 的 Configuration，导致手机系统为浅色时，公告弹窗和页面使用两套
         * 夜间状态。这里先同步持久化，再同步 AppCompat，最后由调用方 recreate。
         */
        preferences(context).edit().putInt(KEY_APPEARANCE_MODE, mode).commit();
        syncMaterialNightMode(mode);
    }

    /** Application 启动时调用，让 Material 弹窗与 App 自定义显示模式从第一帧就一致。 */
    public static void syncMaterialNightMode(Context context) {
        syncMaterialNightMode(currentAppearance(context));
    }

    static int appCompatNightMode(int appearance) {
        if (appearance == APPEARANCE_DARK) return AppCompatDelegate.MODE_NIGHT_YES;
        if (appearance == APPEARANCE_LIGHT) return AppCompatDelegate.MODE_NIGHT_NO;
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    private static void syncMaterialNightMode(int appearance) {
        int requested = appCompatNightMode(appearance);
        if (AppCompatDelegate.getDefaultNightMode() != requested) {
            AppCompatDelegate.setDefaultNightMode(requested);
        }
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
