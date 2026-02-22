package it.cavallium.vertx.rpcservice;

import io.vertx.core.eventbus.ReplyException;

/**
 * Exception that wraps a remote service error, preserving the remote stacktrace information.
 */
public class RemoteServiceException extends RuntimeException {

	private final int failureCode;

	public RemoteServiceException(int failureCode, String remoteMessage, String remoteStackTrace) {
		super(buildMessage(failureCode, remoteMessage, remoteStackTrace));
		this.failureCode = failureCode;
	}

	public RemoteServiceException(ReplyException replyException) {
		super(replyException.getMessage(), replyException);
		this.failureCode = replyException.failureCode();
	}

	private static String buildMessage(int failureCode, String remoteMessage, String remoteStackTrace) {
		var sb = new StringBuilder();
		sb.append("Remote service error (code ").append(failureCode).append("): ").append(remoteMessage);
		if (remoteStackTrace != null && !remoteStackTrace.isEmpty()) {
			sb.append("\nRemote stacktrace:\n").append(remoteStackTrace);
		}
		return sb.toString();
	}

	public int getFailureCode() {
		return failureCode;
	}
}
