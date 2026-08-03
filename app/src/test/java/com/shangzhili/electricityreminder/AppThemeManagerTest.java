package com.shangzhili.electricityreminder;

import static org.junit.Assert.assertEquals;

import androidx.appcompat.app.AppCompatDelegate;

import org.junit.Test;

/** 防止 Material 弹窗与 Activity 再次使用不同的深浅模式映射。 */
public final class AppThemeManagerTest {
    @Test
    public void explicitDarkMapsToAppCompatNightYes() {
        assertEquals(
                AppCompatDelegate.MODE_NIGHT_YES,
                AppThemeManager.appCompatNightMode(AppThemeManager.APPEARANCE_DARK)
        );
    }

    @Test
    public void explicitLightMapsToAppCompatNightNo() {
        assertEquals(
                AppCompatDelegate.MODE_NIGHT_NO,
                AppThemeManager.appCompatNightMode(AppThemeManager.APPEARANCE_LIGHT)
        );
    }

    @Test
    public void systemModeRemainsFollowSystem() {
        assertEquals(
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                AppThemeManager.appCompatNightMode(AppThemeManager.APPEARANCE_FOLLOW_SYSTEM)
        );
    }
}
