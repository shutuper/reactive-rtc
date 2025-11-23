package com.qqsuccubus.core.hash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Skeleton-based hierarchical weighted rendezvous hashing with O(log n) lookup.
 */
public final class SkeletonWeightedRendezvousHash {

    private static final int SEED = 0x9747b28c;

    private final int fanout;      // branching factor f (>= 2)
    private final int maxLeafSize; // max real nodes per leaf (m >= 1)

    private record Node(String name, int weight) {
            private Node(String name, int weight) {
                this.name = Objects.requireNonNull(name, "name");
                if (weight <= 0) {
                    throw new IllegalArgumentException("weight must be > 0");
                }
                this.weight = weight;
            }
        }

    /**
     * @param start    inclusive index into State.nodes
     * @param end      exclusive
     * @param children null or empty for leaf
     * @param depth    root depth = 0
     */
    private record Segment(int start, int end, double totalWeight,
                           SkeletonWeightedRendezvousHash.Segment[] children,
                           int depth) {

        boolean isLeaf() {
                return children == null || children.length == 0;
            }

            String id() {
                // Stable identifier within a given State
                return depth + ":" + start + "-" + end;
            }
        }

    /**
     * @param nodes         immutable snapshot of nodes
     * @param weightsByName immutable name -> weight
     * @param root          skeleton root (may be null if no nodes)
     */
    private record State(List<Node> nodes, Map<String, Integer> weightsByName, Segment root) {
            private State(List<Node> nodes, Map<String, Integer> weightsByName, Segment root) {
                this.nodes = Collections.unmodifiableList(nodes);
                this.weightsByName = Collections.unmodifiableMap(weightsByName);
                this.root = root;
            }
        }

    private final AtomicReference<State> stateRef = new AtomicReference<>();

    public SkeletonWeightedRendezvousHash(int fanout, int maxLeafSize, Map<String, Integer> initialNodes) {
        if (fanout < 2) {
            throw new IllegalArgumentException("fanout must be >= 2");
        }
        if (maxLeafSize < 1) {
            throw new IllegalArgumentException("maxLeafSize must be >= 1");
        }
        this.fanout = fanout;
        this.maxLeafSize = maxLeafSize;

        List<Node> nodes = new ArrayList<>();
        Map<String, Integer> weights = new HashMap<>();
        if (initialNodes != null) {
            for (Map.Entry<String, Integer> e : initialNodes.entrySet()) {
                String name = e.getKey();
                int w = e.getValue();
                if (w <= 0) {
                    continue; // skip invalid weights
                }
                nodes.add(new Node(name, w));
                weights.put(name, w);
            }
        }
        Segment root = buildSkeleton(nodes);
        stateRef.set(new State(nodes, weights, root));
    }

    public SkeletonWeightedRendezvousHash(Map<String, Integer> initialNodes) {
        this(4, 8, initialNodes); // default fanout and leaf size
    }

    // --------------- public API ---------------

    /**
     * Select a node for the given clientId.
     */
    public String selectNode(String clientId) {
        Objects.requireNonNull(clientId, "clientId");
        State state = stateRef.get();
        if (state.root == null) {
            throw new IllegalStateException("No nodes configured");
        }

        Segment seg = state.root;

        // descend skeleton O(log n): at each level choose among <= fanout children
        while (!seg.isLeaf()) {
            Segment bestChild = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (Segment child : seg.children) {
                if (child.totalWeight <= 0) {
                    continue;
                }
                double score = weightedScore(clientId, child.id(), child.totalWeight);
                if (score > bestScore) {
                    bestScore = score;
                    bestChild = child;
                }
            }
            if (bestChild == null) {
                // fallback: shouldn't normally happen
                break;
            }
            seg = bestChild;
        }

        // leaf: choose among real nodes in [start, end) (<= maxLeafSize, обмежена константа)
        Node bestNode = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = seg.start; i < seg.end; i++) {
            Node n = state.nodes.get(i);
            double score = weightedScore(clientId, n.name, n.weight);
            if (score > bestScore) {
                bestScore = score;
                bestNode = n;
            }
        }
        if (bestNode == null) {
            throw new IllegalStateException("No nodes in selected segment");
        }
        return bestNode.name;
    }

    /**
     * Add a new node or update weight of existing node.
     * Thread-safe and non-blocking (uses atomic snapshot replacement).
     */
    public void addOrUpdateNode(String nodeName, int weight) {
        Objects.requireNonNull(nodeName, "nodeName");
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be > 0");
        }

        stateRef.updateAndGet(oldState -> {
            List<Node> newNodes = new ArrayList<>(oldState.nodes.size() + 1);
            boolean updated = false;
            for (Node n : oldState.nodes) {
                if (n.name.equals(nodeName)) {
                    newNodes.add(new Node(nodeName, weight));
                    updated = true;
                } else {
                    newNodes.add(n);
                }
            }
            if (!updated) {
                newNodes.add(new Node(nodeName, weight));
            }

            Map<String, Integer> newWeights = new HashMap<>(oldState.weightsByName);
            newWeights.put(nodeName, weight);

            Segment newRoot = buildSkeleton(newNodes);
            return new State(newNodes, newWeights, newRoot);
        });
    }

    /**
     * Remove node. If it does not exist, this is a no-op.
     */
    public void removeNode(String nodeName) {
        Objects.requireNonNull(nodeName, "nodeName");
        stateRef.updateAndGet(oldState -> {
            if (!oldState.weightsByName.containsKey(nodeName)) {
                return oldState; // nothing to remove
            }
            List<Node> newNodes = new ArrayList<>(oldState.nodes.size() - 1);
            for (Node n : oldState.nodes) {
                if (!n.name.equals(nodeName)) {
                    newNodes.add(n);
                }
            }
            Map<String, Integer> newWeights = new HashMap<>(oldState.weightsByName);
            newWeights.remove(nodeName);

            Segment newRoot = buildSkeleton(newNodes);
            return new State(newNodes, newWeights, newRoot);
        });
    }

    /** Snapshot of node names. */
    public List<String> getNodeNames() {
        State s = stateRef.get();
        List<String> names = new ArrayList<>(s.nodes.size());
        for (Node n : s.nodes) {
            names.add(n.name);
        }
        return Collections.unmodifiableList(names);
    }

    /** Snapshot of node weights. */
    public Map<String, Integer> getWeights() {
        return stateRef.get().weightsByName;
    }

    // --------------- internal: skeleton builder ---------------

    private Segment buildSkeleton(List<Node> nodes) {
        if (nodes.isEmpty()) {
            return null;
        }
        int n = nodes.size();
        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) {
            prefix[i + 1] = prefix[i] + nodes.get(i).weight;
        }
        return buildSegment(nodes, prefix, 0, n, 0);
    }

    private Segment buildSegment(List<Node> nodes, double[] prefix, int start, int end, int depth) {
        int len = end - start;
        double totalWeight = prefix[end] - prefix[start];
        if (len <= maxLeafSize) {
            return new Segment(start, end, totalWeight, null, depth);
        }
        int childCount = Math.min(fanout, len);
        Segment[] children = new Segment[childCount];
        int baseSize = len / childCount;
        int remainder = len % childCount;
        int offset = start;
        for (int i = 0; i < childCount; i++) {
            int size = baseSize + (i < remainder ? 1 : 0);
            int childEnd = offset + size;
            children[i] = buildSegment(nodes, prefix, offset, childEnd, depth + 1);
            offset = childEnd;
        }
        return new Segment(start, end, totalWeight, children, depth);
    }

    // --------------- internal: weighted rendezvous score ---------------

    /**
     * Weighted HRW через Gumbel-trick:
     * score = log(weight) + Gumbel(hash(clientId, candidateId))
     */
    private double weightedScore(String clientId, String candidateId, double weight) {
        if (weight <= 0.0) {
            return Double.NEGATIVE_INFINITY;
        }
        String key = clientId + "|" + candidateId;
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        int hash = murmur3_32(bytes, SEED);
        long unsigned = hash & 0xffffffffL;
        if (unsigned == 0L) {
            unsigned = 1L; // avoid 0
        }
        double u = (unsigned + 0.5d) / (double) (1L << 32); // in (0,1)
        double gumbel = -Math.log(-Math.log(u));
        return Math.log(weight) + gumbel;
    }

    // --------------- internal: Murmur3 32-bit ---------------

    private static int murmur3_32(byte[] data, int seed) {
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;

        int h1 = seed;
        int len = data.length;
        int roundedEnd = len & 0xfffffffc; // 4 byte blocks

        for (int i = 0; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | ((data[i + 3] & 0xff) << 24);

            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;

            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;

        switch (len & 0x03) {
            case 3:
                k1 ^= (data[roundedEnd + 2] & 0xff) << 16;
            case 2:
                k1 ^= (data[roundedEnd + 1] & 0xff) << 8;
            case 1:
                k1 ^= (data[roundedEnd] & 0xff);
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h1 ^= k1;
        }

        h1 ^= len;
        h1 ^= (h1 >>> 16);
        h1 *= 0x85ebca6b;
        h1 ^= (h1 >>> 13);
        h1 *= 0xc2b2ae35;
        h1 ^= (h1 >>> 16);

        return h1;
    }

}
