package utb.fai;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.text.html.parser.ParserDelegator;

public class App {

    private static final Pattern CHARSET_PATTERN = Pattern.compile(
            "(?i)charset\s*=\s*[\"']?([^\s\"';/>]+)");

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Missing parameter - start URL");
            return;
        }

        int maxDepth = 2;
        int debugLevel = 0;
        if (args.length >= 2) {
            try {
                maxDepth = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid maxDepth, using 2");
            }
        }
        if (args.length >= 3) {
            try {
                debugLevel = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid debugLevel, using 0");
            }
        }
        if (maxDepth < 0) {
            System.err.println("Invalid maxDepth, using 0");
            maxDepth = 0;
        }

        try {
            URI startURI = normalizeURI(new URI(args[0]));
            if (startURI == null || (!"http".equalsIgnoreCase(startURI.getScheme())
                    && !"https".equalsIgnoreCase(startURI.getScheme()))) {
                System.err.println("Start URL must use HTTP or HTTPS");
                return;
            }

            ConcurrentHashMap<URI, Boolean> visitedURIs = new ConcurrentHashMap<>();
            ConcurrentLinkedQueue<URIinfo> foundURIs = new ConcurrentLinkedQueue<>();
            ConcurrentHashMap<String, Integer> wordCounts = new ConcurrentHashMap<>();

            visitedURIs.put(startURI, Boolean.TRUE);
            foundURIs.add(new URIinfo(startURI, 0));

            int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            AtomicInteger activeTasks = new AtomicInteger(0);

            final int crawlerMaxDepth = maxDepth;
            final int crawlerDebugLevel = debugLevel;

            while (!foundURIs.isEmpty() || activeTasks.get() > 0) {
                while (!foundURIs.isEmpty()) {
                    URIinfo item = foundURIs.poll();
                    if (item == null) {
                        break;
                    }

                    activeTasks.incrementAndGet();
                    final URIinfo work = item;
                    executor.execute(() -> {
                        try {
                            processPage(work, crawlerMaxDepth, crawlerDebugLevel, visitedURIs, foundURIs,
                                    wordCounts);
                        } finally {
                            activeTasks.decrementAndGet();
                        }
                    });
                }

                if (activeTasks.get() > 0) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            executor.shutdown();
            printTopWords(wordCounts, 20);
        } catch (Exception e) {
            System.err.println("Crawler terminated safely after an unexpected error.");
            if (debugLevel > 0) {
                e.printStackTrace(System.err);
            }
        }
    }

    private static void processPage(URIinfo work, int maxDepth, int debugLevel,
            ConcurrentHashMap<URI, Boolean> visitedURIs,
            ConcurrentLinkedQueue<URIinfo> foundURIs,
            ConcurrentHashMap<String, Integer> wordCounts) {
        if (work.depth > maxDepth) {
            return;
        }

        if (debugLevel > 0) {
            System.err.println("Analyzing " + work.uri);
        }

        try {
            HttpURLConnection connection = (HttpURLConnection) work.uri.toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "UTB-FAI-WebCrawler/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 400) {
                return;
            }

            byte[] bytes;
            try (InputStream stream = connection.getInputStream()) {
                bytes = stream.readAllBytes();
            }

            Charset charset = charsetFromHeader(connection.getContentType());
            if (charset == null) {
                charset = charsetFromMeta(bytes);
            }

            String html = charset == null
                    ? new String(bytes, StandardCharsets.UTF_8)
                    : charset.decode(ByteBuffer.wrap(bytes)).toString();

            ParserCallback callback = new ParserCallback(visitedURIs, foundURIs, wordCounts);
            callback.depth = work.depth;
            callback.maxDepth = maxDepth;
            callback.pageURI = work.uri;
            callback.debugLevel = debugLevel;

            ParserDelegator parser = new ParserDelegator();
            parser.parse(new java.io.StringReader(html), callback, true);

        } catch (Exception e) {
            if (debugLevel > 0) {
                System.err.println("Error loading page " + work.uri + ": " + e.getMessage());
            }
        }
    }

    private static Charset charsetFromHeader(String contentType) {
        if (contentType == null) {
            return null;
        }
        Matcher matcher = CHARSET_PATTERN.matcher(contentType);
        if (matcher.find()) {
            return safeCharset(matcher.group(1));
        }
        return null;
    }

    private static Charset charsetFromMeta(byte[] bytes) {
        int length = Math.min(bytes.length, 8192);
        String prefix = new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
        Matcher matcher = CHARSET_PATTERN.matcher(prefix);
        if (matcher.find()) {
            return safeCharset(matcher.group(1));
        }
        return null;
    }

    private static Charset safeCharset(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static URI normalizeURI(URI uri) {
        try {
            URI normalized = new URI(uri.getScheme(), uri.getAuthority(),
                    uri.getPath().isEmpty() ? "/" : uri.getPath(), uri.getQuery(), null);
            return normalized.normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static void printTopWords(Map<String, Integer> wordCounts, int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(wordCounts.entrySet());
        entries.sort(Comparator
                .comparing(Map.Entry<String, Integer>::getValue).reversed()
                .thenComparing(Map.Entry::getKey));

        int shown = 0;
        for (Map.Entry<String, Integer> entry : entries) {
            System.out.println(entry.getKey() + ";" + entry.getValue());
            if (++shown >= limit) {
                break;
            }
        }
    }
}
