package com.shangzhili.electricityreminder;

/**
 * 校付宝楼栋、楼层和房间列表中的一个可选项。
 *
 * <p>界面只向用户展示 {@link #name}，后续接口请求和本地配置使用 {@link #code}。
 * 使用同一个简单模型承载三级列表，可以避免为结构完全相同的数据创建重复类。</p>
 */
public final class DirectoryOption {
    public final String code;
    public final String name;

    public DirectoryOption(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
