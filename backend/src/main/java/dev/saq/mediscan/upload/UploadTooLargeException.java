package dev.saq.mediscan.upload;

/**
 * Thrown when an upload's bytes exceed the configured limit while being streamed to disk.
 *
 * <p>Distinct from Spring's {@code MaxUploadSizeExceededException}, which fires on the
 * declared {@code Content-Length}. Both end up as {@code 413 FILE_TOO_LARGE}; this one is the
 * check that holds when the declared length is absent or a lie.
 */
public class UploadTooLargeException extends RuntimeException {

	private final long maxBytes;

	public UploadTooLargeException(long maxBytes) {
		super("upload exceeds " + maxBytes + " bytes", null, false, false);
		this.maxBytes = maxBytes;
	}

	public long maxBytes() {
		return maxBytes;
	}
}
