package com.celfit.instagram.source.self;

/**
 * 자체크롤 실패 — errorClass가 FailoverInstagramSource의 라우팅을 결정한다. surface는 어느
 * 표면(embed/wpi/og/feed/comment)에서 났는지 관측용으로 붙인다(nullable — SelfCrawlBackend.run()을
 * 거치지 않고 직접 던져진 경우 비어 있을 수 있다. {@link #withSurface}로 나중에 부착 가능).
 */
public class SelfCrawlException extends RuntimeException {

	private final transient SelfErrorClass errorClass;
	private final String surface;

	public SelfCrawlException(SelfErrorClass errorClass, String message) {
		this(errorClass, message, null, null);
	}

	public SelfCrawlException(SelfErrorClass errorClass, String message, Throwable cause) {
		this(errorClass, message, cause, null);
	}

	public SelfCrawlException(SelfErrorClass errorClass, String message, Throwable cause, String surface) {
		super(message, cause);
		this.errorClass = errorClass;
		this.surface = surface;
	}

	public SelfErrorClass errorClass() {
		return errorClass;
	}

	public String surface() {
		return surface;
	}

	/** surface가 이미 있으면 그대로, 없으면 같은 errorClass·message·cause에 surface만 부착한 새 예외. */
	public SelfCrawlException withSurface(String surface) {
		if (this.surface != null) {
			return this;
		}
		return new SelfCrawlException(errorClass, getMessage(), getCause(), surface);
	}
}
