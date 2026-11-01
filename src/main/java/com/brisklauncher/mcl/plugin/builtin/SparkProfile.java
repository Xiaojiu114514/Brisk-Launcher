package com.brisklauncher.mcl.plugin.builtin;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.zip.GZIPInputStream;

/**
 * spark 的 {@code .sparkprofile} 读取器（纯 JDK，不需要 protobuf 执行期）。
 *
 * <p>档案是 spark 的 protobuf（{@code spark.spark_sampler.proto} / {@code spark.spark.proto}）：
 * <pre>
 *   SamplerData {
 *     metadata = 1 (SamplerMetadata)      // 时间、TPS、MSPT、记忆体、GC、系统资讯、模组清单
 *     threads  = 2 (repeated ThreadNode)  // 取样到的呼叫树（每个执行绪一棵）
 *   }
 *   ThreadNode     { name=1, children=3, times=4, children_refs=5 }
 *   StackTraceNode { class_name=3, method_name=4, method_desc=7, times=8, children_refs=9 }
 * </pre>
 * {@code times} 是「每个时间窗的毫秒数」（含子节点），所以
 * <b>自身耗时 = 节点时间 − 子节点时间</b>，据此算出「热点」百分比（与 spark 网页的 Flat 检视一致）。
 *
 * <p>这里只实作 wire format 的最小读取器（varint / fixed64 / length-delimited），
 * 未知栏位一律跳过——spark 之后加栏位也不会坏。
 */
public final class SparkProfile {

    private SparkProfile() {
    }

    /** 一個模組/插件來源（spark 的 sources map）。 */
    public record Source(String name, String version) {
    }

    /** 熱點：自身耗時百分比 + 位置。 */
    public record Hotspot(double percent, String className, String methodName, double selfMillis) {
        public String display() {
            return className + "#" + methodName;
        }
    }

    /** 執行緒摘要。 */
    public record ThreadSummary(String name, double totalMillis) {
    }

    /** 解析結果。 */
    public record Profile(
            long startTime, long endTime, int intervalMicros, int ticks, int samplerMode,
            String platformType, String platformVersion, String minecraftVersion, int sparkVersion,
            boolean hasTps, double tps1m, double tps5m, double tps15m,
            boolean hasMspt, double msptMean, double msptMedian, double msptMax, double mspt95,
            long heapUsed, long heapTotal,
            boolean hasGc, long gcTotal, double gcAvgTime,
            boolean hasCpu, double cpuProcess1m, double cpuSystem1m, String cpuModel, int cpuThreads,
            String javaVendor, String javaVersion, String osName, String osArch,
            int playerCount, int totalEntities,
            List<Source> sources, List<ThreadSummary> threads, List<Hotspot> hotspots) {

        public double durationMillis() {
            long end = endTime > 0 ? endTime : startTime;
            return Math.max(0, end - startTime);
        }

        public double worstTps() {
            if (!hasTps) {
                return -1;
            }
            return Math.min(tps1m, Math.min(tps5m, tps15m));
        }

        /** 最差的 MSPT（5m 視窗抓不到時用 1m）。 */
        public double worstMspt() {
            if (!hasMspt) {
                return -1;
            }
            return Math.max(msptMean, Math.max(mspt95, Math.max(msptMedian, 0)));
        }
    }

    // ---------- 对外入口 ----------

    /** 這個檔案看起來像 spark 的 profile 嗎？（protobuf 或 gzip 壓縮的 protobuf） */
    public static boolean looksLikeProfile(Path file) {
        try {
            byte[] head = new byte[4];
            try (var input = Files.newInputStream(file)) {
                if (input.read(head) < 2) {
                    return false;
                }
            }
            if ((head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) {
                return true;
            }
            // protobuf 的第一个 byte 是 tag：field 1 wire type 2 → 0x0A（后面的长度可能是多位元组）
            return head[0] == 0x0A;
        } catch (Exception error) {
            return false;
        }
    }

    /**
     * 读取并解析 {@code .sparkprofile}。
     *
     * <p>安全审计 v2 A-01：以前直接 {@code Files.readAllBytes} + {@code gzip.readAllBytes()}，
     * 在 192MB 堆下，一个 1KB 的 gzip 炸弹就能把启动器打死。现在两段都有上限。
     */
    public static Profile read(Path file) throws Exception {
        byte[] data = com.brisklauncher.mcl.security.InputBudget.readPossiblyGzip(file);
        return read(data);
    }

    public static Profile read(byte[] data) {
        Builder builder = new Builder();
        Pb pb = new Pb(data);
        while (pb.hasMore()) {
            int tag = pb.tag();
            int field = tag >>> 3;
            int wire = tag & 7;
            switch (field) {
                case 1 -> builder.metadata(pb.bytes(wire));
                case 2 -> builder.thread(pb.bytes(wire));
                default -> pb.skip(wire);
            }
        }
        return builder.build();
    }

    // ---------- 内部建构 ----------

    private static final class Builder {
        private long startTime;
        private long endTime;
        private int intervalMicros;
        private int ticks;
        private int samplerMode;
        private String platformType = "server";
        private String platformVersion = "";
        private String minecraftVersion = "";
        private int sparkVersion;
        private boolean hasTps;
        private double tps1m;
        private double tps5m;
        private double tps15m;
        private boolean hasMspt;
        private double msptMean;
        private double msptMedian;
        private double msptMax;
        private double mspt95;
        private long heapUsed;
        private long heapTotal;
        private boolean hasGc;
        private long gcTotal;
        private double gcAvgTime;
        private boolean hasCpu;
        private double cpuProcess1m;
        private double cpuSystem1m;
        private String cpuModel = "";
        private int cpuThreads;
        private String javaVendor = "";
        private String javaVersion = "";
        private String osName = "";
        private String osArch = "";
        private int playerCount;
        private int totalEntities;
        private final Map<String, String> sources = new LinkedHashMap<>();

        private final List<ThreadNode> threads = new ArrayList<>();
        private final Map<String, double[]> selfTimes = new LinkedHashMap<>();
        private double totalMillis;

        void metadata(byte[] data) {
            Pb pb = new Pb(data);
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                switch (field) {
                    case 2 -> startTime = pb.varint(wire);
                    case 3 -> intervalMicros = (int) pb.varint(wire);
                    case 7 -> platform(pb.bytes(wire));
                    case 8 -> platformStatistics(pb.bytes(wire));
                    case 9 -> systemStatistics(pb.bytes(wire));
                    case 11 -> endTime = pb.varint(wire);
                    case 12 -> ticks = (int) pb.varint(wire);
                    case 13 -> sourceEntry(pb.bytes(wire));
                    case 15 -> samplerMode = (int) pb.varint(wire);
                    default -> pb.skip(wire);
                }
            }
        }

        private void platform(byte[] data) {
            Pb pb = new Pb(data);
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                switch (field) {
                    case 1 -> platformType = switch ((int) pb.varint(wire)) {
                        case 1 -> "client";
                        case 2 -> "proxy";
                        default -> "server";
                    };
                    case 3 -> platformVersion = pb.string(wire);
                    case 4 -> minecraftVersion = pb.string(wire);
                    case 7 -> sparkVersion = (int) pb.varint(wire);
                    default -> pb.skip(wire);
                }
            }
        }

        private void platformStatistics(byte[] data) {
            Pb pb = new Pb(data);
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                switch (field) {
                    case 1 -> memory(pb.bytes(wire));
                    case 2 -> gcEntry(pb.bytes(wire));
                    case 4 -> tps(pb.bytes(wire));
                    case 5 -> mspt(pb.bytes(wire));
                    case 7 -> playerCount = (int) pb.varint(wire);
                    case 8 -> world(pb.bytes(wire));
                    default -> pb.skip(wire);
                }
            }
        }

        private void memory(byte[] data) {
            Pb pb = new Pb(data);
            while (pb.hasMore()) {
                int tag = pb.tag();
                if ((tag >>> 3) == 1 && (tag & 7) == 2) {
                    Pb pool = new Pb(pb.bytes(2));
                    while (pool.hasMore()) {
                        int poolTag = pool.tag();
                        if ((poolTag >>> 3) == 1) {
                            heapUsed = pool.varint(poolTag & 7);
                        } else if ((poolTag >>> 3) == 2) {
                            heapTotal = pool.varint(poolTag & 7);
                        } else {
                            pool.skip(poolTag & 7);
                        }
                    }
                } else {
                    pb.skip(tag & 7);
                }
            }
        }

        private void gcEntry(byte[] data) {
            Pb pb = new Pb(data);
            long total = 0;
            double avgTime = 0;
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                if (field == 1) {
                    pb.string(wire);
                } else if (field == 2) {
                    Pb gc = new Pb(pb.bytes(wire));
                    while (gc.hasMore()) {
                        int gcTag = gc.tag();
                        int gcField = gcTag >>> 3;
                        int gcWire = gcTag & 7;
                        if (gcField == 1) {
                            total += gc.varint(gcWire);
                        } else if (gcField == 2) {
                            avgTime = Math.max(avgTime, gc.double64(gcWire));
                        } else {
                            gc.skip(gcWire);
                        }
                    }
                } else {
                    pb.skip(wire);
                }
            }
            if (total > 0) {
                hasGc = true;
                gcTotal += total;
                gcAvgTime = Math.max(gcAvgTime, avgTime);
            }
        }

        private void tps(byte[] data) {
            Pb pb = new Pb(data);
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                if (wire == 1 || wire == 5) {
                    double value = wire == 1 ? pb.double64(wire) : pb.float32(wire);
                    switch (field) {
                        case 1 -> tps1m = value;
                        case 2 -> tps5m = value;
                        case 3 -> tps15m = value;
                        default -> {
                        }
                    }
                    hasTps = true;
                } else {
                    pb.skip(wire);
                }
            }
            if (!hasTps) {
                // 有些版本三个值都是 0（尚未取样）→ 不算有 TPS
                hasTps = tps1m > 0 || tps5m > 0 || tps15m > 0;
            }
        }

        private void mspt(byte[] data) {
            Pb pb = new Pb(data);
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                if (field == 1 || field == 2) {
                    Pb rolling = new Pb(pb.bytes(wire));
                    while (rolling.hasMore()) {
                        int rTag = rolling.tag();
                        int rField = rTag >>> 3;
                        int rWire = rTag & 7;
                        if (rWire != 1 && rWire != 5) {
                            rolling.skip(rWire);
                            continue;
                        }
                        double value = rWire == 1 ? rolling.double64(rWire) : rolling.float32(rWire);
                        switch (rField) {
                            case 1 -> {
                                msptMean = Math.max(msptMean, value);
                                hasMspt = true;
                            }
                            case 4 -> msptMedian = Math.max(msptMedian, value);
                            case 2 -> msptMax = Math.max(msptMax, value);
                            case 5 -> mspt95 = Math.max(mspt95, value);
                            default -> {
                            }
                        }
                    }
                } else {
                    pb.skip(wire);
                }
            }
        }

        private void world(byte[] data) {
            Pb pb = new Pb(data);
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                if (field == 1) {
                    totalEntities = (int) pb.varint(wire);
                } else {
                    pb.skip(wire);
                }
            }
        }

        private void systemStatistics(byte[] data) {
            Pb pb = new Pb(data);
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                switch (field) {
                    case 1 -> cpu(pb.bytes(wire));
                    case 5 -> os(pb.bytes(wire));
                    case 6 -> java(pb.bytes(wire));
                    default -> pb.skip(wire);
                }
            }
        }

        private void cpu(byte[] data) {
            Pb pb = new Pb(data);
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                switch (field) {
                    case 1 -> cpuThreads = (int) pb.varint(wire);
                    case 2 -> usage(pb.bytes(wire), true);
                    case 3 -> usage(pb.bytes(wire), false);
                    case 4 -> cpuModel = pb.string(wire);
                    default -> pb.skip(wire);
                }
            }
        }

        private void usage(byte[] data, boolean process) {
            Pb pb = new Pb(data);
            double last1m = 0;
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                if (field == 1 && (wire == 1 || wire == 5)) {
                    last1m = wire == 1 ? pb.double64(wire) : pb.float32(wire);
                } else {
                    pb.skip(wire);
                }
            }
            hasCpu = true;
            if (process) {
                cpuProcess1m = last1m;
            } else {
                cpuSystem1m = last1m;
            }
        }

        private void os(byte[] data) {
            Pb pb = new Pb(data);
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                switch (field) {
                    case 1 -> osArch = pb.string(wire);
                    case 2 -> osName = pb.string(wire);
                    default -> pb.skip(wire);
                }
            }
        }

        private void java(byte[] data) {
            Pb pb = new Pb(data);
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                switch (field) {
                    case 1 -> javaVendor = pb.string(wire);
                    case 2 -> javaVersion = pb.string(wire);
                    default -> pb.skip(wire);
                }
            }
        }

        private void sourceEntry(byte[] data) {
            Pb pb = new Pb(data);
            String key = "";
            String name = "";
            String version = "";
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                if (field == 1) {
                    key = pb.string(wire);
                } else if (field == 2) {
                    Pb value = new Pb(pb.bytes(wire));
                    while (value.hasMore()) {
                        int vTag = value.tag();
                        int vField = vTag >>> 3;
                        int vWire = vTag & 7;
                        if (vField == 1) {
                            name = value.string(vWire);
                        } else if (vField == 2) {
                            version = value.string(vWire);
                        } else {
                            value.skip(vWire);
                        }
                    }
                } else {
                    pb.skip(wire);
                }
            }
            String label = name.isBlank() ? key : name;
            if (!label.isBlank()) {
                sources.put(label, version);
            }
        }

        void thread(byte[] data) {
            Pb pb = new Pb(data);
            ThreadNode node = new ThreadNode();
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                switch (field) {
                    case 1 -> node.name = pb.string(wire);
                    case 3 -> node.nodes.add(StackTraceNodeReader.read(new Pb(pb.bytes(wire))));
                    case 4 -> node.times.addAll(pb.doubles(wire));
                    default -> pb.skip(wire);
                }
            }
            node.link();
            threads.add(node);
        }

        Profile build() {
            computeHotspots();
            List<ThreadSummary> summaries = new ArrayList<>();
            for (ThreadNode node : threads) {
                summaries.add(new ThreadSummary(node.name, node.total()));
            }
            summaries.sort((a, b) -> Double.compare(b.totalMillis(), a.totalMillis()));
            List<Hotspot> hotspots = new ArrayList<>();
            for (Map.Entry<String, double[]> entry : selfTimes.entrySet()) {
                double self = entry.getValue()[0];
                if (self <= 0 || totalMillis <= 0) {
                    continue;
                }
                int split = entry.getKey().indexOf('#');
                String className = split < 0 ? entry.getKey() : entry.getKey().substring(0, split);
                String methodName = split < 0 ? "" : entry.getKey().substring(split + 1);
                hotspots.add(new Hotspot(self * 100.0 / totalMillis, className, methodName, self));
            }
            hotspots.sort((a, b) -> Double.compare(b.selfMillis(), a.selfMillis()));
            if (hotspots.size() > 15) {
                hotspots = new ArrayList<>(hotspots.subList(0, 15));
            }
            List<Source> sourceList = new ArrayList<>();
            for (Map.Entry<String, String> entry : sources.entrySet()) {
                sourceList.add(new Source(entry.getKey(), entry.getValue()));
            }
            sourceList.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            return new Profile(startTime, endTime, intervalMicros, ticks, samplerMode,
                    platformType, platformVersion, minecraftVersion, sparkVersion,
                    hasTps, tps1m, tps5m, tps15m,
                    hasMspt, msptMean, msptMedian, msptMax, mspt95,
                    heapUsed, heapTotal,
                    hasGc, gcTotal, gcAvgTime,
                    hasCpu, cpuProcess1m, cpuSystem1m, cpuModel, cpuThreads,
                    javaVendor, javaVersion, osName, osArch,
                    playerCount, totalEntities, sourceList, summaries, hotspots);
        }

        /**
         * 算「自身耗时」：每个节点是含子节点的时间，自己 = 自己 − 子节点。
         *
         * <p>{@code children_refs} 指向同一执行绪的 children 阵列（spark 为了省空间用的索引表），
         * 少数版本没有 refs 就退化成「children 按顺序即子节点」。
         */
        private void computeHotspots() {
            for (ThreadNode node : threads) {
                totalMillis = Math.max(totalMillis, node.total());
                for (Node root : node.nodes) {
                    collectSelf(root, new java.util.HashSet<>());
                }
            }
        }

        /** 遞迴累加自身耗時（走整棵樹，只算一次）。 */
        private void collectSelf(Node node, java.util.Set<Node> seen) {
            if (!seen.add(node)) {
                return;
            }
            double self = node.selfMillis();
            if (self > 0) {
                selfTimes.computeIfAbsent(node.key(), key -> new double[1])[0] += self;
            }
            for (Node child : node.children) {
                collectSelf(child, seen);
            }
        }
    }

    /** 原始呼叫樹節點（把 protobuf 讀完後才展開 refs）。 */
    private static final class Node {
        String className = "";
        String methodName = "";
        double[] times = new double[0];
        int[] childRefs = new int[0];
        final List<Node> children = new ArrayList<>();

        double total() {
            double max = 0;
            for (double value : times) {
                max = Math.max(max, value);
            }
            return max;
        }

        /** 自身耗時 = 含子節點的時間 − 子節點時間加總。 */
        double selfMillis() {
            double childSum = 0;
            for (Node child : children) {
                childSum += child.total();
            }
            return Math.max(0, total() - childSum);
        }

        String key() {
            return className + "#" + methodName;
        }
    }

    /** 一個執行緒的呼叫樹。 */
    private static final class ThreadNode {
        String name = "";
        final List<Node> nodes = new ArrayList<>();
        final List<Double> times = new ArrayList<>();

        double total() {
            double max = 0;
            for (double value : times) {
                max = Math.max(max, value);
            }
            for (Node node : nodes) {
                if (node.childRefs.length == 0) {
                    max = Math.max(max, node.total());
                }
            }
            return max;
        }

        /**
         * 把 {@code children_refs} 展开成真正的父子关系。
         *
         * <p>spark 的作法：所有节点放在同一层阵列，每个节点用索引指向自己的子节点
         * （省掉 protobuf 的递回巢状）。没有被任何人指到的就是根。
         */
        void link() {
            int count = nodes.size();
            boolean[] referenced = new boolean[count];
            for (Node node : nodes) {
                for (int ref : node.childRefs) {
                    if (ref < 0 || ref >= count || ref == nodes.indexOf(node)) {
                        continue;
                    }
                    if (!node.children.contains(nodes.get(ref))) {
                        node.children.add(nodes.get(ref));
                    }
                    referenced[ref] = true;
                }
            }
            List<Node> roots = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if (!referenced[i]) {
                    roots.add(nodes.get(i));
                }
            }
            nodes.clear();
            nodes.addAll(roots);
        }
    }

    /** 把 StackTraceNode 的 protobuf 讀成 {@link Node}。 */
    private static final class StackTraceNodeReader {
        static Node read(Pb pb) {
            Node node = new Node();
            List<Integer> refs = new ArrayList<>();
            while (pb.hasMore()) {
                int tag = pb.tag();
                int field = tag >>> 3;
                int wire = tag & 7;
                switch (field) {
                    case 3 -> node.className = pb.string(wire);
                    case 4 -> node.methodName = pb.string(wire);
                    case 8 -> {
                        List<Double> values = pb.doubles(wire);
                        double[] merged = new double[values.size()];
                        for (int i = 0; i < values.size(); i++) {
                            merged[i] = values.get(i);
                        }
                        node.times = merged;
                    }
                    case 9 -> {
                        for (long value : pb.varints(wire)) {
                            refs.add((int) value);
                        }
                    }
                    default -> pb.skip(wire);
                }
            }
            node.childRefs = new int[refs.size()];
            for (int i = 0; i < refs.size(); i++) {
                node.childRefs[i] = refs.get(i);
            }
            return node;
        }
    }

    /**
     * 最小 protobuf 读取器（只读，不写）。
     *
     * <p>支援 varint / length-delimited / fixed64 / fixed32；未知栏位用 {@link #skip(int)} 跳过。
     */
    private static final class Pb {
        private final byte[] data;
        private int pos;

        Pb(byte[] data) {
            this.data = data;
        }

        boolean hasMore() {
            return pos < data.length;
        }

        int position() {
            return pos;
        }

        int tag() {
            return (int) rawVarint();
        }

        long varint(int wire) {
            if (wire != 0) {
                skip(wire);
                return 0;
            }
            return rawVarint();
        }

        String string(int wire) {
            if (wire != 2) {
                skip(wire);
                return "";
            }
            int length = (int) rawVarint();
            int available = clamp(length);
            String text = new String(data, pos, available, StandardCharsets.UTF_8);
            advance(length);
            return text;
        }

        byte[] bytes(int wire) {
            if (wire != 2) {
                skip(wire);
                return new byte[0];
            }
            int length = (int) rawVarint();
            int available = clamp(length);
            byte[] copy = new byte[available];
            System.arraycopy(data, pos, copy, 0, available);
            advance(length);
            return copy;
        }

        double double64(int wire) {
            if (wire != 1) {
                skip(wire);
                return 0;
            }
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                bits |= (long) (data[pos + i] & 0xFF) << (8 * i);
            }
            pos += 8;
            return Double.longBitsToDouble(bits);
        }

        float float32(int wire) {
            if (wire != 5) {
                skip(wire);
                return 0;
            }
            int bits = 0;
            for (int i = 0; i < 4; i++) {
                bits |= (data[pos + i] & 0xFF) << (8 * i);
            }
            pos += 4;
            return Float.intBitsToFloat(bits);
        }

        /** repeated double：可能是 packed（wire 2）或逐個 fixed64（wire 1）。 */
        List<Double> doubles(int wire) {
            List<Double> values = new ArrayList<>();
            if (wire == 1) {
                values.add(double64(1));
            } else if (wire == 2) {
                int length = (int) rawVarint();
                int end = pos + clamp(length);
                while (pos + 8 <= end) {
                    long bits = 0;
                    for (int i = 0; i < 8; i++) {
                        bits |= (long) (data[pos + i] & 0xFF) << (8 * i);
                    }
                    pos += 8;
                    values.add(Double.longBitsToDouble(bits));
                }
                // 直接把读取位置移到栏位结尾（不能再 advance(length)，会多跳一次）
                pos = Math.min(end, data.length);
            } else {
                skip(wire);
            }
            return values;
        }

        /** repeated int32：packed（wire 2）或逐個 varint（wire 0）。 */
        List<Long> varints(int wire) {
            List<Long> values = new ArrayList<>();
            if (wire == 0) {
                values.add(rawVarint());
            } else if (wire == 2) {
                int length = (int) rawVarint();
                int end = pos + clamp(length);
                int guard = 0;
                while (pos < end && guard++ < 100_000) {
                    int before = pos;
                    values.add(rawVarint());
                    if (pos == before) {
                        break;
                    }
                }
                pos = Math.min(end, data.length);
            } else {
                skip(wire);
            }
            return values;
        }

        void skip(int wire) {
            switch (wire) {
                case 0 -> rawVarint();
                case 1 -> pos += 8;
                case 2 -> {
                    // 注意：不能写成 `pos += rawVarint()`——复合赋值会先读 pos 再呼叫，
                    // 让「读长度时已前进的位移」被吃掉（这里踩过：metadata 整个错位）。
                    int length = (int) rawVarint();
                    pos += length;
                }
                case 5 -> pos += 4;
                default -> pos = data.length;
            }
            if (pos > data.length) {
                pos = data.length;
            }
        }

        /** 實際可讀長度（不會超過緩衝區）。 */
        private int clamp(int length) {
            if (length <= 0) {
                return 0;
            }
            return Math.min(length, Math.max(0, data.length - pos));
        }

        private void advance(int length) {
            pos += Math.max(0, length);
            if (pos > data.length) {
                pos = data.length;
            }
        }

        private long rawVarint() {
            long result = 0;
            int shift = 0;
            while (pos < data.length) {
                int current = data[pos++] & 0xFF;
                result |= (long) (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 63) {
                    return result;
                }
            }
            return result;
        }
    }
}
