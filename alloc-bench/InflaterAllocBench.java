import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.springframework.boot.loader.jar.NestedJarFile;

/**
 * Deterministic micro-benchmark for the nested jar inflater buffer.
 * <p>
 * Opens every {@code BOOT-INF/lib/*.jar} of a Spring Boot fat jar with the loader's
 * {@link NestedJarFile} (whichever loader jar is on the classpath) and reads every
 * deflated entry, exactly like class loading does. It reports the bytes allocated by the
 * reading thread (exact, from {@code ThreadMXBean}, not sampled) and the inflater input
 * buffer bytes predicted by the old and the new sizing rule.
 * <p>
 * Usage: {@code java -cp <loader.jar> InflaterAllocBench.java <fat.jar> [classes|all] [iterations]}
 */
public class InflaterAllocBench {

	public static void main(String[] args) throws Exception {
		File fatJar = new File(args[0]);
		boolean classesOnly = args.length < 2 || "classes".equals(args[1]);
		int iterations = (args.length > 2) ? Integer.parseInt(args[2]) : 5;
		List<String> nested = nestedJars(fatJar);
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory
			.getThreadMXBean();
		Stats stats = null;
		long[] allocated = new long[iterations];
		long[] nanos = new long[iterations];
		for (int i = 0; i < iterations; i++) {
			System.gc();
			long startAlloc = threads.getCurrentThreadAllocatedBytes();
			long start = System.nanoTime();
			stats = readAll(fatJar, nested, classesOnly);
			nanos[i] = System.nanoTime() - start;
			allocated[i] = threads.getCurrentThreadAllocatedBytes() - startAlloc;
		}
		String loader = NestedJarFile.class.getProtectionDomain().getCodeSource().getLocation().getPath();
		System.out.printf("loader                     : %s%n", loader.substring(loader.lastIndexOf('/') + 1));
		System.out.printf("nested jars                : %d%n", nested.size());
		System.out.printf("deflated entries read      : %d (%s)%n", stats.entries, classesOnly ? "*.class" : "all");
		System.out.printf("uncompressed bytes         : %,d%n", stats.uncompressed);
		System.out.printf("compressed bytes           : %,d%n", stats.compressed);
		System.out.printf("buffer bytes, old rule     : %,d   (sized from uncompressed size)%n", stats.oldRuleBuffers);
		System.out.printf("buffer bytes, new rule     : %,d   (sized from compressed size)%n", stats.newRuleBuffers);
		System.out.printf("entries w/ bigger buffer   : %d   (uncompressed > 64K but compressed <= 64K)%n",
				stats.grewEntries);
		for (int i = 0; i < iterations; i++) {
			System.out.printf("iteration %d                : allocated=%,d bytes  time=%d ms%n", i + 1, allocated[i],
					nanos[i] / 1_000_000);
		}
		System.out.printf("RESULT allocated-bytes=%d time-ms=%d%n", allocated[iterations - 1],
				nanos[iterations - 1] / 1_000_000);
	}

	private static List<String> nestedJars(File fatJar) throws IOException {
		List<String> names = new ArrayList<>();
		try (JarFile jar = new JarFile(fatJar)) {
			jar.stream()
				.map(ZipEntry::getName)
				.filter((name) -> name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar"))
				.forEach(names::add);
		}
		return names;
	}

	private static Stats readAll(File fatJar, List<String> nested, boolean classesOnly) throws IOException {
		Stats stats = new Stats();
		byte[] sink = new byte[8192];
		for (String name : nested) {
			try (NestedJarFile jar = new NestedJarFile(fatJar, name)) {
				Enumeration<JarEntry> entries = jar.entries();
				while (entries.hasMoreElements()) {
					JarEntry entry = entries.nextElement();
					if (entry.isDirectory() || entry.getMethod() != ZipEntry.DEFLATED
							|| (classesOnly && !entry.getName().endsWith(".class"))) {
						continue;
					}
					try (InputStream in = jar.getInputStream(entry)) {
						// Same access pattern as class loading (Resource.getBytes -> readAllBytes)
						in.readAllBytes();
					}
					stats.add(entry);
				}
			}
		}
		return stats;
	}

	/** Mirrors ZipInflaterInputStream.getInflaterBufferSize. */
	static int bufferSize(long size) {
		size += 2;
		size = (size > 65536) ? 8192 : size;
		size = (size <= 0) ? 4096 : size;
		return (int) size;
	}

	static class Stats {

		long entries;

		long uncompressed;

		long compressed;

		long oldRuleBuffers;

		long newRuleBuffers;

		long grewEntries;

		void add(JarEntry entry) {
			this.entries++;
			this.uncompressed += entry.getSize();
			this.compressed += entry.getCompressedSize();
			int oldSize = bufferSize(entry.getSize());
			int newSize = bufferSize(entry.getCompressedSize());
			this.oldRuleBuffers += oldSize;
			this.newRuleBuffers += newSize;
			if (newSize > oldSize) {
				this.grewEntries++;
			}
		}

	}

}
