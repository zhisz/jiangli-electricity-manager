package com.shangzhili.electricityreminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 保证桌面宠物语录池可循环读取，不会因连续点击出现越界或空白气泡。 */
public final class HeroQuotesTest {
    @Test public void quotePoolContainsEnoughProjectRelatedMessages() {
        assertTrue(HeroQuotes.size() >= 6);
        for (int i = 0; i < HeroQuotes.size(); i++) {
            assertFalse(HeroQuotes.at(i).trim().isEmpty());
        }
    }

    @Test public void indexesWrapInBothDirections() {
        assertEquals(HeroQuotes.at(0), HeroQuotes.at(HeroQuotes.size()));
        assertEquals(HeroQuotes.at(HeroQuotes.size() - 1), HeroQuotes.at(-1));
    }
}
