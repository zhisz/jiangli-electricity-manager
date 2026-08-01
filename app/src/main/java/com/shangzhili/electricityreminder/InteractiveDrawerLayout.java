package com.shangzhili.electricityreminder;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * 与手指一比一联动的轻量侧滑容器。
 *
 * <p>没有使用“滑过阈值后立刻打开页面”的离散手势，而是把水平位移直接映射到抽屉的
 * translationX。用户不松手，基地就停留在手指所在进度；松手后才结合距离和速度决定
 * 展开或收回。抽屉固定占屏幕 5/6，右侧始终保留主页作为返回方向提示。</p>
 */
public final class InteractiveDrawerLayout extends FrameLayout {
    interface DrawerStateListener { void onDrawerStateChanged(boolean open); }
    private static final float DRAWER_WIDTH_RATIO = 5f / 6f;
    private static final long MAX_SETTLE_MILLIS = 240L;

    private final int touchSlop;
    private final int minimumFlingVelocity;
    private View drawer;
    private View scrim;
    private VelocityTracker velocityTracker;
    private float downX;
    private float downY;
    private float startTranslation;
    private boolean dragging;
    private boolean drawerOpen;
    private boolean positionInitialized;
    private boolean outsideTap;
    private DrawerStateListener stateListener;

    public InteractiveDrawerLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minimumFlingVelocity = configuration.getScaledMinimumFlingVelocity();
    }

    public void bind(View drawerView, View scrimView) {
        drawer = drawerView;
        scrim = scrimView;
        drawer.setElevation(dp(18));
        scrim.setVisibility(INVISIBLE);
    }

    public void setDrawerStateListener(DrawerStateListener listener) {
        stateListener = listener;
    }

    public boolean isDrawerOpen() { return drawerOpen; }

    public void openDrawer(boolean animate) { settle(true, animate); }
    public void closeDrawer(boolean animate) { settle(false, animate); }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (drawer != null) {
            int drawerWidth = Math.round(MeasureSpec.getSize(widthMeasureSpec) * DRAWER_WIDTH_RATIO);
            drawer.measure(
                    MeasureSpec.makeMeasureSpec(drawerWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY)
            );
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (drawer == null) return;
        drawer.layout(0, 0, drawer.getMeasuredWidth(), getMeasuredHeight());
        if (!positionInitialized && drawer.getWidth() > 0) {
            positionInitialized = true;
            // 主题切换会重建 Activity；若上一个实例正在展示基地，首次布局直接保持展开，
            // 不先闪回主页再重新打开。
            setDrawerTranslation(drawerOpen ? 0f : -drawer.getWidth());
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (drawer == null || drawer.getWidth() == 0) return false;
        trackVelocity(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                abortSettling();
                downX = event.getX();
                downY = event.getY();
                startTranslation = drawer.getTranslationX();
                dragging = false;
                outsideTap = drawerOpen && downX > drawer.getWidth();
                return outsideTap;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.25f
                        && ((drawerOpen && dx < 0) || (!drawerOpen && dx > 0))) {
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                recycleVelocity();
                break;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (drawer == null) return false;
        trackVelocity(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_MOVE:
                if (outsideTap) return true;
                dragging = true;
                setDrawerTranslation(clamp(startTranslation + event.getX() - downX));
                return true;
            case MotionEvent.ACTION_UP:
                if (outsideTap) {
                    outsideTap = false;
                    closeDrawer(true);
                } else {
                    velocityTracker.computeCurrentVelocity(1000);
                    float velocityX = velocityTracker.getXVelocity();
                    float progress = 1f + drawer.getTranslationX() / drawer.getWidth();
                    boolean open = DrawerGestureMath.shouldOpen(
                            progress, velocityX, minimumFlingVelocity);
                    settle(open, true);
                }
                recycleVelocity();
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                settle(drawerOpen, true);
                recycleVelocity();
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    private void settle(boolean open, boolean animate) {
        if (drawer == null) return;
        drawerOpen = open;
        float target = open ? 0f : -drawer.getWidth();
        if (!animate || drawer.getWidth() == 0) {
            setDrawerTranslation(target);
            notifyState(open);
            return;
        }
        float distanceRatio = Math.abs(target - drawer.getTranslationX()) / drawer.getWidth();
        long duration = Math.max(90L, Math.round(MAX_SETTLE_MILLIS * distanceRatio));
        drawer.animate().cancel();
        drawer.animate().translationX(target).setDuration(duration)
                .setUpdateListener(animation -> updateLayers())
                .withEndAction(() -> notifyState(open)).start();
    }

    private void abortSettling() {
        if (drawer != null) drawer.animate().cancel();
        if (drawer != null && drawer.getWidth() > 0) {
            drawerOpen = drawer.getTranslationX() > -drawer.getWidth() / 2f;
        }
    }

    private void setDrawerTranslation(float translation) {
        drawer.setTranslationX(clamp(translation));
        updateLayers();
    }

    /** 主页遮罩仅做低强度层次提示，右侧仍能辨认原页面内容和返回方向。 */
    private void updateLayers() {
        if (drawer == null || drawer.getWidth() == 0 || scrim == null) return;
        float progress = 1f + drawer.getTranslationX() / drawer.getWidth();
        progress = Math.max(0f, Math.min(1f, progress));
        scrim.setVisibility(progress <= 0f ? INVISIBLE : VISIBLE);
        scrim.setAlpha(.16f * progress);
    }

    private float clamp(float translation) {
        return DrawerGestureMath.clampTranslation(translation, drawer.getWidth());
    }

    private void trackVelocity(MotionEvent event) {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);
    }

    private void recycleVelocity() {
        if (velocityTracker != null) velocityTracker.recycle();
        velocityTracker = null;
    }

    private void notifyState(boolean open) {
        if (stateListener != null) stateListener.onDrawerStateChanged(open);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
