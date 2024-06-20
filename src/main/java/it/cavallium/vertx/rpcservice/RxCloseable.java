package it.cavallium.vertx.rpcservice;

import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Closeable;
import io.vertx.core.Promise;

public interface RxCloseable extends Closeable, AutoCloseable {

	@Override
	default void close(Promise<Void> completion) {
		rxClose().subscribe(completion::complete, completion::fail);
	}

	/**
	 * Use {@link #close(Promise)}
	 */
	@Deprecated
	@Override
	default void close() {
		rxClose().blockingAwait();
	}

	Completable rxClose();
}
