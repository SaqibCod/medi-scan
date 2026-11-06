package dev.saq.mediscan.extract;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in for the {@code tesseract} program, so the process plumbing can be tested against a
 * real child process without needing Tesseract installed.
 *
 * <p>Run as {@code java -cp <test classes> dev.saq.mediscan.extract.FakeTesseract <mode> …}.
 * Using the JVM already running the tests as the executable keeps this working identically on
 * Windows and Linux, and - the point of the exercise - involves <strong>no shell</strong>. A
 * {@code .bat} or {@code .sh} stand-in would need {@code cmd.exe} or {@code sh} to launch it,
 * which is exactly the thing the production code refuses to do.
 *
 * <p>Every mode writes bytes directly rather than using {@code System.out.println}, so output
 * sizes are exact and line endings do not drift between platforms.
 */
public final class FakeTesseract {

	/** A canary the stderr test looks for. If it ever reaches a log, that test fails. */
	public static final String STDERR_CANARY = "ZZSTDERR-SECRET-4417";

	private FakeTesseract() {
	}

	public static void main(String[] args) throws Exception {
		String mode = args.length == 0 ? "tsv" : args[0];
		PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
		PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);

		switch (mode) {
			case "tsv" -> {
				out.print(validTsv());
				out.flush();
			}
			case "empty" -> {
				// A valid header and no words: what Tesseract emits for a blank page.
				out.print("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\t"
						+ "left\ttop\twidth\theight\tconf\ttext\n");
				out.flush();
			}
			case "sleep" -> {
				// Long enough that any sane test timeout fires first. The test asserts the
				// process is killed rather than waited for.
				Thread.sleep(600_000);
			}
			case "flood" -> {
				// More than any cap a test will set, written in a loop so the reader hits the
				// cap mid-stream and has to kill us.
				byte[] chunk = "x".repeat(4096).getBytes(StandardCharsets.UTF_8);
				for (int i = 0; i < 100_000; i++) {
					System.out.write(chunk);
				}
				System.out.flush();
			}
			case "exit1" -> {
				err.print("Error, cannot read input file /tmp/whatever.png: No such file\n");
				err.flush();
				System.exit(1);
			}
			case "stderr" -> {
				// Valid output, plus a secret on stderr. Tesseract really does put the input
				// path in its stderr, which is why none of it may be logged.
				err.print("Estimating resolution as 241\n");
				err.print(STDERR_CANARY + "\n");
				err.flush();
				out.print(validTsv());
				out.flush();
			}
			case "echo-args" -> {
				// Every argument as received, one per line - but emitted as TSV word rows,
				// because the engine pipes stdout through TsvLineBuilder and plain text would
				// parse to an empty page. One paragraph with incrementing line numbers, so the
				// lines come back one per argument with no blank paragraph breaks between them.
				// Lets a test prove the arguments arrived literally rather than through a
				// shell that would have expanded them.
				out.print("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\t"
						+ "left\ttop\twidth\theight\tconf\ttext\n");
				for (int i = 0; i < args.length; i++) {
					out.print("5\t1\t1\t1\t" + (i + 1) + "\t1\t10\t" + (i * 30)
							+ "\t100\t20\t95.0\t" + args[i] + "\n");
				}
				out.flush();
			}
			default -> {
				err.print("unknown mode\n");
				System.exit(2);
			}
		}
	}

	/** One result row, enough for the engine to parse into a page with known confidences. */
	private static String validTsv() {
		return """
				level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext
				1\t1\t0\t0\t0\t0\t0\t0\t2480\t1100\t-1\t
				5\t1\t1\t1\t1\t1\t83\t100\t158\t20\t96.723816\tPotassium
				5\t1\t1\t1\t1\t2\t533\t100\t47\t20\t94.454147\t5.4
				5\t1\t1\t1\t1\t3\t710\t100\t106\t20\t92.634727\tmmol/L
				5\t1\t1\t1\t1\t4\t893\t100\t120\t20\t89.585075\t3.5-5.0
				""";
	}
}
