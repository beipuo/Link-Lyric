// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
package com.linklyric.core.lyric;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Reads media-timed TTML lyrics and Apple Music/AMLL absolute timestamps. */
public final class TtmlParser {
    private static final String TTML = "http://www.w3.org/ns/ttml";
    private static final String METADATA = TTML + "#metadata";
    private static final String ITUNES = "http://music.apple.com/lyric-ttml-internal";
    private static final Pattern CLOCK = Pattern.compile("(?:([0-9]{2,8}):)?([0-5]?[0-9]):([0-5][0-9](?:\\.[0-9]+)?)");
    private static final Pattern OFFSET = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(h|m|s|ms)?");
    private static final Pattern SPACE = Pattern.compile("[ \\t\\r\\n]+");
    private static final long UNBOUNDED = Long.MAX_VALUE;
    private static final long LAST_LINE_MS = 4000;

    private TtmlParser() {}

    /** Returns an empty timeline for invalid XML or a document without usable timed lines. */
    public static LyricTimeline parse(String xml, long trackDurationMs) {
        if (xml == null || xml.isBlank()) return LyricTimeline.empty();
        try {
            var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("jdk.xml.maxElementDepth", "64");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            String source = xml.startsWith("\uFEFF") ? xml.substring(1) : xml;
            Element root = builder.parse(new InputSource(new StringReader(source))).getDocumentElement();
            if (!is(root, "tt")) return LyricTimeline.empty();

            boolean absolute = root.hasAttributeNS(ITUNES, "timing");
            List<Cue> cues = new ArrayList<>();
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element body && is(body, "body")) {
                    collect(body, new Range(0, UNBOUNDED), absolute, cues);
                }
            }
            cues.sort(Comparator.comparingLong(cue -> cue.range().start()));
            return timeline(cues, trackDurationMs, absolute);
        } catch (IOException | SAXException | ParserConfigurationException e) {
            return LyricTimeline.empty();
        }
    }

    private static void collect(Element element, Range parent, boolean absolute, List<Cue> cues) {
        if ("seq".equals(element.getAttribute("timeContainer"))) return;
        Range range = range(element, parent, absolute);
        if (range == null) return;
        if (is(element, "p")) {
            cues.add(new Cue(element, range));
            return;
        }
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && (is(child, "div") || is(child, "p"))) {
                collect(child, range, absolute, cues);
            }
        }
    }

    private static LyricTimeline timeline(List<Cue> cues, long duration, boolean absolute) {
        List<LyricLine> lines = new ArrayList<>();
        int next = 0;
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            long start = cue.range().start();
            long end = cue.range().end();
            if (!cue.element().hasAttribute("end") && !cue.element().hasAttribute("dur")) {
                while (next < cues.size() && cues.get(next).range().start() <= start) next++;
                long inferred = next < cues.size() ? cues.get(next).range().start()
                        : duration > start ? duration : add(start, LAST_LINE_MS);
                end = Math.min(end, inferred);
            }
            if (end <= start) continue;

            List<Piece> pieces = new ArrayList<>();
            List<String> translations = new ArrayList<>();
            collectText(cue.element(), new Range(start, end), absolute, false, pieces, translations);
            String text = String.join("", pieces.stream().map(Piece::text).toList());
            if (text.isBlank()) continue;
            String translation = translations.isEmpty() ? "" : translations.get(0);
            lines.add(new LyricLine(text, translation, start, end, words(pieces),
                    cue.element().getAttributeNS(METADATA, "agent")));
        }
        return lines.isEmpty() ? LyricTimeline.empty() : new LyricTimeline(lines);
    }

    private static void collectText(Element element, Range range, boolean absolute, boolean timed,
                                    List<Piece> pieces, List<String> translations) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                String text = SPACE.matcher(node.getNodeValue()).replaceAll(" ");
                if (!text.isEmpty()) pieces.add(new Piece(text, timed ? range : null));
            } else if (node instanceof Element span) {
                if (is(span, "br")) pieces.add(new Piece(" ", null));
                if (!is(span, "span")) continue;
                String role = span.getAttributeNS(METADATA, "role");
                if ("x-translation".equals(role)) {
                    String translation = SPACE.matcher(span.getTextContent()).replaceAll(" ").strip();
                    if (!translation.isEmpty()) translations.add(translation);
                } else if (!"x-roman".equals(role) && !"x-bg".equals(role)) {
                    Range spanRange = range(span, range, absolute);
                    collectText(span, spanRange == null ? range : spanRange, absolute,
                            spanRange != null && (timed || span.hasAttribute("begin")), pieces, translations);
                }
            }
        }
    }

    private static List<LyricWord> words(List<Piece> pieces) {
        List<LyricWord> words = new ArrayList<>();
        StringBuilder prefix = new StringBuilder();
        for (Piece piece : pieces) {
            Range timing = piece.timing();
            if (timing == null) {
                if (!piece.text().isBlank()) return List.of();
                if (words.isEmpty()) prefix.append(piece.text());
                else {
                    LyricWord previous = words.remove(words.size() - 1);
                    words.add(new LyricWord(previous.text() + piece.text(), previous.startMs(), previous.endMs()));
                }
            } else {
                if (!words.isEmpty() && timing.start() < words.get(words.size() - 1).startMs()) return List.of();
                words.add(new LyricWord(prefix + piece.text(), timing.start(), timing.end()));
                prefix.setLength(0);
            }
        }
        // End an open span at the next onset if it inherited a longer paragraph bound.
        for (int i = 0; i + 1 < words.size(); i++) {
            LyricWord word = words.get(i);
            long nextStart = words.get(i + 1).startMs();
            if (nextStart > word.startMs() && nextStart < word.endMs()) {
                words.set(i, new LyricWord(word.text(), word.startMs(), nextStart));
            }
        }
        return words;
    }

    private static Range range(Element element, Range parent, boolean absolute) {
        String begin = element.getAttribute("begin");
        String end = element.getAttribute("end");
        String dur = element.getAttribute("dur");
        long start = begin.isEmpty() ? parent.start() : position(begin, parent.start(), absolute);
        long stop = end.isEmpty() ? UNBOUNDED : position(end, parent.start(), absolute);
        long length = dur.isEmpty() ? UNBOUNDED : time(dur);
        if (start < 0 || stop < 0 || length < 0) return null;
        stop = Math.min(parent.end(), Math.min(stop, add(start, length)));
        start = Math.max(parent.start(), start);
        return stop > start ? new Range(start, stop) : null;
    }

    private static long position(String value, long origin, boolean absolute) {
        long time = time(value);
        if (time < 0 || absolute) return time;
        return add(origin, time);
    }

    private static long time(String raw) {
        String value = raw.strip();
        var clock = CLOCK.matcher(value);
        double millis;
        if (clock.matches()) {
            long hours = clock.group(1) == null ? 0 : Long.parseLong(clock.group(1));
            millis = hours * 3_600_000L + Integer.parseInt(clock.group(2)) * 60_000L
                    + Double.parseDouble(clock.group(3)) * 1000;
        } else {
            var offset = OFFSET.matcher(value);
            if (!offset.matches()) return -1;
            String unit = offset.group(2);
            long factor = unit == null ? 1000 : switch (unit) {
                case "h" -> 3_600_000;
                case "m" -> 60_000;
                case "ms" -> 1;
                default -> 1000;
            };
            millis = Double.parseDouble(offset.group(1)) * factor;
        }
        return millis < UNBOUNDED ? Math.round(millis) : -1;
    }

    private static long add(long start, long offset) {
        return offset > UNBOUNDED - start ? UNBOUNDED : start + offset;
    }

    private static boolean is(Element element, String name) {
        String namespace = element.getNamespaceURI();
        return name.equals(element.getLocalName()) && (namespace == null || TTML.equals(namespace));
    }

    private record Range(long start, long end) {}
    private record Cue(Element element, Range range) {}
    private record Piece(String text, Range timing) {}
}
