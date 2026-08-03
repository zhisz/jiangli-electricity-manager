package com.shangzhili.electricityreminder;

/** 服务器公告的最小不可变模型，不包含设备、房间或任何用户配置。 */
public final class Announcement {
    public final long id;
    public final String title;
    public final String content;
    public final String publishedAt;

    public Announcement(long id, String title, String content, String publishedAt) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.content = content == null ? "" : content;
        this.publishedAt = publishedAt == null ? "" : publishedAt;
    }
}
