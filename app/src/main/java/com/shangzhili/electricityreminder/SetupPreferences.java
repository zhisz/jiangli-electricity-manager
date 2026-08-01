package com.shangzhili.electricityreminder;

import android.content.Context;
import android.content.SharedPreferences;

/** 保存无法由标准 Android API 自动判断的首次引导与厂商自启动确认状态。 */
public final class SetupPreferences {
    private final SharedPreferences preferences;

    public SetupPreferences(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                "setup_preferences", Context.MODE_PRIVATE
        );
    }

    public boolean hasShownOnboarding() {
        return preferences.getBoolean("onboardingShown", false);
    }

    public void markOnboardingShown() {
        preferences.edit().putBoolean("onboardingShown", true).apply();
    }

    public boolean hasShownFormalReleaseNotice() {
        return preferences.getBoolean("formalReleaseNoticeShown", false);
    }

    public void markFormalReleaseNoticeShown() {
        preferences.edit().putBoolean("formalReleaseNoticeShown", true).apply();
    }

    public boolean isAutoStartConfirmed() {
        return preferences.getBoolean("autoStartConfirmed", false);
    }

    public void setAutoStartConfirmed(boolean confirmed) {
        preferences.edit().putBoolean("autoStartConfirmed", confirmed).apply();
    }
}
