import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * Summarises JFR recordings produced by run-benchmark.sh.
 * <p>
 * Usage: {@code java JfrAnalyze.java <label> <file.jfr>...}. Prints one TSV row per
 * recording plus an average row. Allocation numbers are the sum of
 * {@code jdk.ObjectAllocationSample} weights (an unbiased estimate of allocated bytes).
 */
public class JfrAnalyze {

	private static final String INFLATER = "org.springframework.boot.loader.jar.ZipInflaterInputStream";

	private static final String LOADER_PACKAGE = "org.springframework.boot.loader.";

	public static void main(String[] args) throws Exception {
		String label = args[0];
		List<double[]> rows = new ArrayList<>();
		for (int i = 1; i < args.length; i++) {
			double[] row = analyze(Path.of(args[i]));
			rows.add(row);
			print(label, Path.of(args[i]).getFileName().toString(), row);
		}
		double[] avg = new double[rows.get(0).length];
		for (double[] row : rows) {
			for (int i = 0; i < row.length; i++) {
				avg[i] += row[i] / rows.size();
			}
		}
		print(label, "AVERAGE", avg);
	}

	static void print(String label, String name, double[] r) {
		System.out.printf(Locale.ROOT, "%s\t%s\t%.1f\t%.1f\t%.1f\t%.0f\t%.1f%n", label, name, r[0] / 1e6, r[1] / 1e6,
				r[2] / 1e6, r[3], r[4]);
	}

	/** @return [total, loader, inflaterInit, gcCount, gcPauseMs] */
	static double[] analyze(Path file) throws Exception {
		double total = 0, loader = 0, inflater = 0, gcCount = 0, gcPauseMs = 0;
		try (RecordingFile recording = new RecordingFile(file)) {
			while (recording.hasMoreEvents()) {
				RecordedEvent event = recording.readEvent();
				switch (event.getEventType().getName()) {
					case "jdk.ObjectAllocationSample" -> {
						long weight = event.getLong("weight");
						total += weight;
						RecordedStackTrace stack = event.getStackTrace();
						if (stack != null) {
							boolean inLoader = false, inInflaterInit = false;
							for (RecordedFrame frame : stack.getFrames()) {
								if (!frame.isJavaFrame()) {
									continue;
								}
								String type = frame.getMethod().getType().getName();
								inLoader |= type.startsWith(LOADER_PACKAGE);
								inInflaterInit |= type.equals(INFLATER) && frame.getMethod().getName().equals("<init>");
							}
							loader += inLoader ? weight : 0;
							inflater += inInflaterInit ? weight : 0;
						}
					}
					case "jdk.GarbageCollection" -> {
						gcCount++;
						gcPauseMs += event.getDuration("sumOfPauses").toNanos() / 1e6;
					}
					default -> {
					}
				}
			}
		}
		return new double[] { total, loader, inflater, gcCount, gcPauseMs };
	}

}
