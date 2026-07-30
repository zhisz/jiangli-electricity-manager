package com.shangzhili.electricityreminder;

/**
 * 本应用固定不变的校付宝参数。
 *
 * <p>应用只服务于江西理工大学南昌校区，因此校区代码可以固定；校付宝会话值则通过
 * {@link BuildConfig} 从开发者本机的 {@code local.properties} 注入，源码中不保存
 * 真实值。这样公开仓库、普通构建日志和代码审查都不会暴露生产会话。</p>
 *
 * <p><strong>安全提醒：</strong>即使从私有配置注入，JID 最终仍会进入 APK，具备反编译
 * 能力的人可以提取它。凭据失效或泄露后应立即替换并重新构建。</p>
 */
public final class AppConstants {
    /** 校付宝查询接口使用的 Cookie；公开构建未配置 JID 时保持为空。 */
    public static final String SHIRO_COOKIE =
            BuildConfig.XIAOFUBAO_SHIRO_JID.trim().isEmpty()
                    ? ""
                    : "shiroJID=" + BuildConfig.XIAOFUBAO_SHIRO_JID.trim();

    /** 江西理工大学南昌校区的固定校区代码。 */
    public static final String AREA_ID = "1902181751257031";

    /** 校付宝 H5 路由和充值配置接口使用的校区层级代码。 */
    public static final String AREA_CODE = "001001";

    /** 江西理工大学在校付宝中的学校代码。 */
    public static final String SCHOOL_CODE = "10407";

    private AppConstants() {
        // 工具类只提供常量，不允许创建实例。
    }
}
