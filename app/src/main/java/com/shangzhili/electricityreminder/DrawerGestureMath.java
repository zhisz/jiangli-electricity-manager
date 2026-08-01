package com.shangzhili.electricityreminder;

/** 侧滑抽屉中与 Android View 无关的判定，便于在 JVM 中覆盖距离和速度边界。 */
final class DrawerGestureMath {
    private DrawerGestureMath() {}

    static boolean shouldOpen(float progress, float velocityX, float minimumFlingVelocity) {
        return Math.abs(velocityX) >= minimumFlingVelocity
                ? velocityX > 0f : progress >= .45f;
    }

    static float clampTranslation(float translation, float drawerWidth) {
        return Math.max(-drawerWidth, Math.min(0f, translation));
    }
}
