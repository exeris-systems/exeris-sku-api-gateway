package eu.exeris.sku.gateway;

import com.sun.management.ThreadMXBean;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Config;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Result;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Measures a workload that runs each iteration on a new virtual thread, which is how the kernel serves a
 * connection, and returns the result in the shape the kernel TCK asserts.
 *
 * <p>{@code JfrAllocationMonitor.measure} reads the allocated bytes of, and keeps the allocation events of,
 * the one thread that calls it. A workload that starts a virtual thread per iteration allocates on other
 * threads, so measuring it that way sees nothing and passes whatever the workload does. This class measures
 * where the work happens instead. Inside each virtual thread it reads the allocated bytes of the carrier
 * thread the virtual thread is mounted on, immediately before and after the iteration; a virtual thread that
 * does not block stays on that carrier between the two reads, so the difference is what the iteration
 * allocated and excludes the cost of creating and scheduling the thread. It keeps the JFR allocation events of
 * {@code eu.exeris.*} types that fall on a virtual thread or a carrier. The {@link Result} it returns is
 * asserted with {@code JfrAllocationMonitor.assertContract}, so the contract and its thresholds are the TCK's.
 *
 * <p>The iterations run one after another, each started and joined from the calling thread.
 */
final class VirtualThreadAllocation {

    private static final String EXERIS_PACKAGE = "eu.exeris.";

    private VirtualThreadAllocation() {
    }

    /** One iteration of the workload; runs on a virtual thread that is new for that iteration. */
    @FunctionalInterface
    interface Iteration {
        void run(int index);
    }

    static Result measure(Config config, Iteration iteration) throws IOException, InterruptedException {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported() ? bean : null;
        if (threads == null) {
            throw new IllegalStateException("This JVM does not report per-thread allocated bytes");
        }
        boolean restore = !threads.isThreadAllocatedMemoryEnabled();
        if (restore) {
            threads.setThreadAllocatedMemoryEnabled(true);
        }
        try {
            Probe probe = new Probe(threads, iteration);
            for (int i = 0; i < config.warmupIterations(); i++) {
                probe.runOnNewVirtualThread(i);
            }
            probe.bytes = 0;

            Path file = Path.of("target", "jfr-reports", config.testClassName() + "-" + config.subsystemName()
                    + "-virtual-" + System.nanoTime() + ".jfr");
            Files.createDirectories(file.getParent());
            try (Recording recording = new Recording()) {
                recording.enable("jdk.ObjectAllocationInNewTLAB");
                recording.enable("jdk.ObjectAllocationOutsideTLAB");
                recording.enable("jdk.ObjectAllocationSample").with("throttle", "off");
                recording.setDestination(file);
                recording.start();
                for (int i = 0; i < config.hotPathIterations(); i++) {
                    probe.runOnNewVirtualThread(i);
                }
                recording.stop();
            }
            return new Result(exerisAllocations(file), probe.bytes, config.hotPathIterations(), file);
        } finally {
            if (restore) {
                threads.setThreadAllocatedMemoryEnabled(false);
            }
        }
    }

    private static List<RecordedEvent> exerisAllocations(Path file) throws IOException {
        List<RecordedEvent> events = new ArrayList<>();
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                if (!type.equals("jdk.ObjectAllocationInNewTLAB") && !type.equals("jdk.ObjectAllocationOutsideTLAB")
                        && !type.equals("jdk.ObjectAllocationSample")) {
                    continue;
                }
                RecordedClass allocated = event.getValue("objectClass");
                RecordedThread thread = event.getThread();
                if (allocated != null && allocated.getName().startsWith(EXERIS_PACKAGE) && thread != null
                        && (thread.isVirtual() || thread.getJavaName() != null
                        && thread.getJavaName().startsWith("ForkJoinPool-"))) {
                    events.add(event);
                }
            }
        }
        return events;
    }

    /** Runs the iteration and brackets it; one instance serves every iteration, so it allocates nothing per one. */
    private static final class Probe implements Runnable {
        private final ThreadMXBean threads;
        private final Iteration iteration;
        private final Map<String, Long> carrierIds = new ConcurrentHashMap<>();
        int index;
        long bytes;
        volatile Throwable failure;

        Probe(ThreadMXBean threads, Iteration iteration) {
            this.threads = threads;
            this.iteration = iteration;
        }

        void runOnNewVirtualThread(int i) throws InterruptedException {
            index = i;
            Thread thread = Thread.ofVirtual().unstarted(this);
            thread.start();
            thread.join();
            Throwable thrown = failure;
            if (thrown != null) {
                throw new IllegalStateException("iteration " + i + " failed", thrown);
            }
        }

        @Override
        public void run() {
            try {
                if (!Thread.currentThread().isVirtual()) {
                    throw new IllegalStateException("the iteration is not on a virtual thread");
                }
                // The carrier is found before the bracket: this allocates, and is not the iteration's cost
                long carrier = carrierId();
                long before = threads.getThreadAllocatedBytes(carrier);
                iteration.run(index);
                long after = threads.getThreadAllocatedBytes(carrier);
                bytes += after - before;
            } catch (Throwable t) {
                failure = t;
            }
        }

        private long carrierId() {
            String description = Thread.currentThread().toString();
            String carrier = description.substring(description.indexOf('@') + 1);
            Long id = carrierIds.get(carrier);
            if (id == null) {
                for (Thread candidate : Thread.getAllStackTraces().keySet()) {
                    if (candidate.getName().equals(carrier)) {
                        carrierIds.put(carrier, candidate.threadId());
                    }
                }
                id = carrierIds.get(carrier);
                if (id == null) {
                    throw new IllegalStateException("carrier thread " + carrier + " not found");
                }
            }
            return id;
        }
    }
}
