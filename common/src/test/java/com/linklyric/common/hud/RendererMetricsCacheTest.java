package com.linklyric.common.hud;

import com.linklyric.common.style.LyricStyleConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RendererMetricsCacheTest {
    @Test
    void textPainterIsReusedOnlyWithinTheFrame() throws Exception {
        var renderer = new FloatingLyricRenderer(new TestFeeds.Simple());
        var painter = new Metrics();
        Method method = FloatingLyricRenderer.class.getDeclaredMethod("textPainter",
                FloatingLyricRenderer.Painter.class);
        method.setAccessible(true);
        assertTrue(renderer.render(painter));
        Object first = method.invoke(renderer, painter);
        assertSame(first, method.invoke(renderer, painter));
        renderer.setStyle(renderer.style().withShadow(false).withStroke(true));
        var replacement = new Metrics();
        assertTrue(renderer.render(replacement));
        assertNotSame(first, method.invoke(renderer, replacement));
        assertFalse(renderer.render(null));
        var field = FloatingLyricRenderer.class.getDeclaredField("frameTextPainter");
        field.setAccessible(true);
        assertNull(field.get(renderer));
    }

    @Test
    void cachedWrapMatchesFreshMetricsAcrossChanges() throws Exception {
        var renderer = new FloatingLyricRenderer(new TestFeeds.Simple());
        renderer.setStyle(LyricStyleConfig.DEFAULT.withWrap(true));
        var painter = new Metrics();
        String text = "一二".repeat(80);
        var first = wrap(renderer, painter, text, 60);
        assertSame(first, wrap(renderer, painter, text, 60));
        assertSame(first, wrap(renderer, new Metrics(), text, 60));
        painter.redistribute = true;
        var redistributed = wrap(renderer, painter, text, 60);
        assertNotSame(first, redistributed);
        assertEquals(LyricWrap.wrap(text, 60, c -> painter.charWidth((char) c)), redistributed);
        painter.ellipsis = 17;
        var ellipsis = wrap(renderer, painter, text, 60);
        assertNotSame(redistributed, ellipsis);
        assertEquals(LyricWrap.wrap(text, 60, c -> painter.charWidth((char) c)), ellipsis);
        for (int width : new int[]{40, 120, 60}) {
            assertEquals(LyricWrap.wrap(text, width, c -> painter.charWidth((char) c)),
                    wrap(renderer, painter, text, width));
        }
        String changed = "二一".repeat(80);
        assertEquals(LyricWrap.wrap(changed, 60, c -> painter.charWidth((char) c)),
                wrap(renderer, painter, changed, 60));
        renderer.setStyle(renderer.style().withWrap(false));
        assertNull(wrap(renderer, painter, text, 60));
        renderer.setStyle(renderer.style().withWrap(true));
        assertEquals(LyricWrap.wrap(text, 60, c -> painter.charWidth((char) c)),
                wrap(renderer, painter, text, 60));
    }

    private static Object wrap(FloatingLyricRenderer renderer, Metrics painter,
                               String text, int width) throws Exception {
        Method method = FloatingLyricRenderer.class.getDeclaredMethod("primarySegments",
                FloatingLyricRenderer.Painter.class, String.class, int.class, int.class);
        method.setAccessible(true);
        return method.invoke(renderer, painter, text, painter.textWidth(text), width);
    }

    private static final class Metrics implements FloatingLyricRenderer.Painter {
        boolean redistribute;
        int ellipsis = 9;
        public int screenWidth() { return 480; }
        public int screenHeight() { return 270; }
        public int charWidth(char c) {
            if (c == '…') return ellipsis;
            return redistribute ? (c == '一' ? 5 : 13) : 9;
        }
        public int textWidth(String text) {
            int width = 0;
            for (int i = 0; i < text.length(); i++) width += charWidth(text.charAt(i));
            return width;
        }
        public void drawText(String text, int x, int y, int color) { }
        public void fillRect(int x, int y, int width, int height, int color) { }
    }
}
