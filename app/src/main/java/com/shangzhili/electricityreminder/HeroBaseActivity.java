package com.shangzhili.electricityreminder;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

/**
 * 电小侠基地的独立承载页。
 *
 * <p>首页主要使用跟手侧滑层；保留此页面是为了兼容已经建立的内部跳转。实际内容行为由
 * {@link HeroBaseController} 统一管理，避免独立页与侧滑层出现两套逻辑。</p>
 */
public final class HeroBaseActivity extends Activity {
    private HeroBaseController controller;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppThemeManager.wrap(base));
    }

    @Override protected void onCreate(Bundle state) {
        AppThemeManager.apply(this);
        super.onCreate(state);
        setContentView(R.layout.activity_hero_base);
        getWindow().setStatusBarColor(getColor(R.color.page_background));
        getWindow().setNavigationBarColor(getColor(R.color.page_background));
        controller = new HeroBaseController(this, findViewById(R.id.heroBasePanel), this::finish);
    }

    @Override protected void onResume() {
        super.onResume();
        controller.onVisible();
    }

    @Override protected void onPause() {
        controller.onHidden();
        super.onPause();
    }
}
