package com.shangzhili.electricityreminder;

/**
 * 远程更新清单解析后的只读数据。
 *
 * <p>{@code forceUpdate} 用于强制所有旧版本升级；{@code minSupportedVersionCode}
 * 用于只淘汰低于某个版本的客户端。两者都由开发者在远程 JSON 中决定，用户无法修改。</p>
 */
public final class UpdateInfo {
    public final int versionCode;
    public final String versionName;
    public final int minSupportedVersionCode;
    public final boolean forceUpdate;
    public final String apkUrl;
    public final String sha256;
    public final String releaseNotes;

    public UpdateInfo(
            int versionCode,
            String versionName,
            int minSupportedVersionCode,
            boolean forceUpdate,
            String apkUrl,
            String sha256,
            String releaseNotes
    ) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.minSupportedVersionCode = minSupportedVersionCode;
        this.forceUpdate = forceUpdate;
        this.apkUrl = apkUrl;
        this.sha256 = sha256;
        this.releaseNotes = releaseNotes;
    }

    /** 当前版本是否必须升级；最低支持版本可以覆盖普通的可选更新策略。 */
    public boolean isMandatoryFor(int currentVersionCode) {
        return forceUpdate || currentVersionCode < minSupportedVersionCode;
    }
}
