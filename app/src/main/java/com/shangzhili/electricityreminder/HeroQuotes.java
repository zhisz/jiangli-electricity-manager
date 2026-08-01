package com.shangzhili.electricityreminder;

/** 电小侠点击互动台词；集中维护，避免页面代码堆叠文案。 */
final class HeroQuotes {
    private static final String[] VALUES = {
            "电量要看早，团战才不会突然掉线！",
            "空调努力工作，我来替你盯住余额。",
            "整点巡逻中，宿舍用电交给我吧！",
            "余额充足，今天也要电力满满！",
            "低余额别慌，首页就能快速去充值。",
            "不用再翻公众号，充值入口已经守在首页。",
            "趋势是估算，当前余额要以最新查询为准哦。",
            "系统设置准备齐全，我才能准时赶来提醒。"
    };

    private HeroQuotes() {}
    static int size() { return VALUES.length; }
    static String at(int index) { return VALUES[Math.floorMod(index, VALUES.length)]; }
}
