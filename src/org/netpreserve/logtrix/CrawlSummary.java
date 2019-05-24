package org.netpreserve.logtrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.base.Strings;
import com.google.common.net.InternetDomainName;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toMap;
import static org.netpreserve.logtrix.CrawlLogUtils.canonicalizeMimeType;

public class CrawlSummary {

    private Stats totals = new Stats();
    private Map<Integer, Stats> statusCodes = new HashMap<>();
    private Map<String, Stats> mimeTypes = new HashMap<>();
    private Map<Long, Stats> sizeHisto = new TreeMap<>();
    private Map<String, Stats> registeredDomains = new HashMap<>();
    private Map<String, Stats> seeds = new HashMap<>();

    // Lookup map to find the seed for each URI
    private static Map<UriPlusDiscoveryPath, UriPlusDiscoveryPath> seedForURI = new HashMap<>();

    /**
     * Builds a global crawl summary (not broken down).
     */
    public static CrawlSummary build(Iterable<CrawlDataItem> log) {
        CrawlSummary summary = new CrawlSummary();
        for (CrawlDataItem item : log) {
            summary.add(item);
        }
        return summary;
    }

    /**
     * Builds a crawl summary grouped by the given key function.
     */
    public static Map<String, CrawlSummary> groupedBy(Iterable<CrawlDataItem> log, Function<CrawlDataItem, String> keyFunction) {
        Map<String, CrawlSummary> map = new HashMap<>();
        for (CrawlDataItem item : log) {
            String key = keyFunction.apply(item);
            map.computeIfAbsent(key, k -> new CrawlSummary()).add(item);
        }
        return map;
    }

    public static Map<String, CrawlSummary> byHost(Iterable<CrawlDataItem> log) {
        return groupedBy(log, item -> {
            try {
                return URI.create(item.getURL()).getHost();
            } catch (Exception e) {
                return "";
            }
        });
    }

    public static Map<String, CrawlSummary> bySeed(Iterable<CrawlDataItem> log) {
        return groupedBy(log, item -> seedForURI.get(new UriPlusDiscoveryPath(item.getURL(), item.getHoppath())).uri);
    }

    public static Map<String, CrawlSummary> byRegisteredDomain(Iterable<CrawlDataItem> log) {
        return groupedBy(log, item -> registeredDomain(item));
    }

    private static String registeredDomain(CrawlDataItem item) {
        try {
            URI uri = null;
            try {
                uri = URI.create(item.getURL());
            } catch (Exception e) {
                //Fallback that picks up most parse errors from illegal characters
                uri = URI.create("http://" + item.getURL().split("/")[2]);
            }
            if (uri.getScheme().equals("dns")) {
                return "dns";
            } else {
                String host = uri.getHost();
                if (host == null) {
                    host = uri.getAuthority();
                }
                InternetDomainName internetDomainName = null;
                try {
                    internetDomainName = InternetDomainName.from(host);
                } catch (Exception e) {
                    //Usually this means host is numeric IP so
                    return host;
                }
                if (internetDomainName.isTopPrivateDomain()) {
                    return internetDomainName.topPrivateDomain().toString();
                } else {
                    return host;
                }
            }
        } catch (Exception e) {
            System.err.println("Could not find registered domain for " + item.getURL() + " " + e.getMessage());
            return "unknown";
        }
    }

    private void add(CrawlDataItem item) {
        String mimeType = canonicalizeMimeType(item.getMimeType());
        //What is this? If there is already a mapping for this mimeType key in the Map, the computeIfAbsent just returns it. Otherwise it creates and inserts a
        //new empty Stats objects and inserts it in into the map as well as returning a reference to it so this item can be added. So its equiv to
        //
        // stats = mimeTypes.get(mimeType)
        // if (stats == null) {
        //      //make a new one, insert it then return that
        // }
        // stats.add(item)
        //
        final UriPlusDiscoveryPath key = new UriPlusDiscoveryPath(item.getURL(), item.getHoppath());
        try {
            seeds.computeIfAbsent(seedForURI.get(key).uri, seed -> new Stats()).add(item);
        } catch (NullPointerException e) {
            System.err.println("Did not find seed for item " + key);
            System.exit(1);
        }
        mimeTypes.computeIfAbsent(mimeType, m -> new Stats()).add(item);
        statusCodes.computeIfAbsent(item.getStatusCode(), code -> new Stats(StatusCodes.describe(code))).add(item);
        // FIXME: pretty sure this bucketing is wrong
        long sizeBucket = (long)Math.pow(8, Math.ceil(Math.log(item.getSize()) / Math.log(8)) + 1);
        sizeHisto.computeIfAbsent(sizeBucket, b -> new Stats(humanSize(sizeBucket))).add(item);
        registeredDomains.computeIfAbsent(registeredDomain(item), d -> new Stats()).add(item);
        totals.add(item);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int e = (int) (Math.log(bytes) / Math.log(1024));
        char c = "KMGTPE".charAt(e - 1);
        return String.format("%d %siB", (long)(bytes / Math.pow(1024, e)), c);
    }


    public Stats getTotals() {
        return totals;
    }

    public Map<Integer, Stats> getStatusCodes() {
        return statusCodes;
    }

    public Map<String, Stats> getMimeTypes() {
        return mimeTypes;
    }

    public Map<Long, Stats> getSizeHisto() {
        return sizeHisto;
    }

    public Map<String, Stats> getRegisteredDomains() {
        return registeredDomains;
    }

    public Map<String, Stats> getSeeds() {
        return seeds;
    }

    enum GroupBy {
        NONE, HOST, RDOMAIN
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws IOException {
        List<String> files = new ArrayList<>();
        Function<Iterable<CrawlDataItem>, Object> summarisier = CrawlSummary::build;
        summarisier = CrawlSummary::bySeed;

        for (int i = 0; i < args.length; i++) {
            files.add(args[i]);
        }

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.enable(INDENT_OUTPUT);
        objectMapper.setSerializationInclusion(NON_NULL);

        try (CrawlLogIterator log = new CrawlLogIterator(Paths.get(files.get(0)))) {
            for (CrawlDataItem item: log) {
                UriPlusDiscoveryPath key = new UriPlusDiscoveryPath(item.getURL(), item.getHoppath());
                UriPlusDiscoveryPath value = null;
                if (item.getHoppath().trim().equals("-")) {
                    value = key;  //So the seed which gave rise to a seed uri was itself, which is logical enough
                } else {
                    value = new UriPlusDiscoveryPath(item.getParentURL(), key.getParentDiscoveryPath());
                }
                seedForURI.put(key, value);
            }
        }

        for (UriPlusDiscoveryPath uriPlusDiscoveryPath: seedForURI.keySet()) {
            int depth = 0;
            boolean found = false;
            while (!found && !seedForURI.get(uriPlusDiscoveryPath).discoveryPath.equals("")) {
                depth ++;
                if (depth > 50) {
                    System.err.println("Looks pathological for " + uriPlusDiscoveryPath);
                    found = true;
                }
                UriPlusDiscoveryPath currentAncestor= seedForURI.get(uriPlusDiscoveryPath);
                UriPlusDiscoveryPath nextParent = seedForURI.get(currentAncestor);
                if (nextParent ==  null) {
                    //Shouldn't happen, but then just assume current ancestor is the seed
                    nextParent = new UriPlusDiscoveryPath(currentAncestor.uri, "");
                }
                seedForURI.put(uriPlusDiscoveryPath, nextParent);
            }
        }

        try (CrawlLogIterator log = new CrawlLogIterator(Paths.get(files.get(0)))) {
            Object summary = summarisier.apply(log);
            objectMapper.writeValue(System.out, summary);
        }
    }

    private static void usage() {
        System.out.println("Usage: CrawlSummary [options...] crawl.log\n" +
                "\n" +
                "Options:\n" +
                "  -g {host,registered-domain}   Group summary by host or registered domain\n" +
                "  -n N                          Limit to top N results");
    }

    static class UriPlusDiscoveryPath {
        String uri;
        String discoveryPath;

        public String getParentDiscoveryPath() {
            if (discoveryPath.length() == 0) {
                return discoveryPath;
            } else {
                return discoveryPath.substring(0, discoveryPath.length() - 1);
            }
        }

        public UriPlusDiscoveryPath(String uri, String discoveryPath) {
            this.uri = uri;
            if (discoveryPath == null || discoveryPath.length() == 0 || discoveryPath.equals("-")) {
                discoveryPath = "";
            }
            this.discoveryPath = discoveryPath;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof UriPlusDiscoveryPath)) return false;
            UriPlusDiscoveryPath that = (UriPlusDiscoveryPath) o;
            return uri.equals(that.uri) &&
                    discoveryPath.equals(that.discoveryPath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(uri, discoveryPath);
        }

        @Override
        public String toString() {
            return "UriPlusDiscoveryPath{" +
                    "uri='" + uri + '\'' +
                    ", discoveryPath='" + discoveryPath + '\'' +
                    '}';
        }
    }

}
