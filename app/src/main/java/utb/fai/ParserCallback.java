package utb.fai;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;

class ParserCallback extends HTMLEditorKit.ParserCallback {

    URI pageURI;
    int depth = 0;
    int maxDepth = 5;
    int debugLevel = 0;

    private final Map<URI, Boolean> visitedURIs;
    private final Queue<URIinfo> foundURIs;
    private final Map<String, Integer> wordCounts;
    private int ignoredTagDepth = 0;

    ParserCallback(HashSet<URI> visitedURIs, LinkedList<URIinfo> foundURIs) {
        this((Map<URI, Boolean>) null, foundURIs, new ConcurrentHashMap<>(), visitedURIs);
    }

    ParserCallback(Map<URI, Boolean> visitedURIs, Queue<URIinfo> foundURIs,
            Map<String, Integer> wordCounts) {
        this(visitedURIs, foundURIs, wordCounts, null);
    }

    private ParserCallback(Map<URI, Boolean> visitedURIs, Queue<URIinfo> foundURIs,
            Map<String, Integer> wordCounts, HashSet<URI> legacyVisitedURIs) {
        this.foundURIs = foundURIs;
        this.wordCounts = wordCounts;
        if (legacyVisitedURIs != null) {
            this.visitedURIs = new HashMap<>();
            for (URI uri : legacyVisitedURIs) {
                this.visitedURIs.put(uri, Boolean.TRUE);
            }
        } else {
            this.visitedURIs = visitedURIs;
        }
    }

    public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
        handleLink(tag, attributes, position);
    }

    public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
        if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE
                || tag == HTML.Tag.HEAD) {
            ignoredTagDepth++;
        }
        handleLink(tag, attributes, position);
    }

    public void handleEndTag(HTML.Tag tag, int position) {
        if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE
                || tag == HTML.Tag.HEAD) {
            if (ignoredTagDepth > 0) {
                ignoredTagDepth--;
            }
        }
    }

    private void handleLink(HTML.Tag tag, MutableAttributeSet attributes, int position) {
        String href = null;
        if (debugLevel > 1) {
            System.err.println("handleStartTag: " + tag + ", pos=" + position
                    + ", attribs=" + attributes);
        }

        if (depth <= maxDepth) {
            if (tag == HTML.Tag.A) {
                href = (String) attributes.getAttribute(HTML.Attribute.HREF);
            } else if (tag == HTML.Tag.FRAME) {
                href = (String) attributes.getAttribute(HTML.Attribute.SRC);
            }
        }

        if (href == null || href.isBlank()) {
            return;
        }

        try {
            URI resolved = pageURI.resolve(href.trim());
            if (!resolved.isOpaque()
                    && ("http".equalsIgnoreCase(resolved.getScheme())
                    || "https".equalsIgnoreCase(resolved.getScheme()))) {
                resolved = normalize(resolved);
                if (depth < maxDepth && visitedURIs.putIfAbsent(resolved, Boolean.TRUE) == null) {
                    foundURIs.add(new URIinfo(resolved, depth + 1));
                    if (debugLevel > 0) {
                        System.err.println("Adding URI: " + resolved);
                    }
                }
            }
        } catch (Exception e) {
            if (debugLevel > 0) {
                System.err.println("Nalezeno nekorektní URI: " + href);
            }
        }
    }

    private URI normalize(URI uri) {
        try {
            URI normalized = new URI(uri.getScheme(), uri.getAuthority(),
                    uri.getPath().isEmpty() ? "/" : uri.getPath(), uri.getQuery(), null);
            return normalized.normalize();
        } catch (Exception e) {
            return uri;
        }
    }

    public void handleText(char[] data, int position) {
        if (ignoredTagDepth > 0 || data == null || data.length == 0) {
            return;
        }

        String text = String.valueOf(data).toLowerCase(Locale.ROOT);
        String[] words = text.split("[^\\p{L}\\p{N}]+");
        for (String word : words) {
            if (!word.isBlank()) {
                wordCounts.merge(word, 1, Integer::sum);
            }
        }
    }
}
