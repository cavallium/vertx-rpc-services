package it.cavallium.vertx.rpcservice;

import io.reactivex.rxjava3.core.Completable;
import io.vertx.core.Closeable;
import io.vertx.core.Promise;

public interface RxCloseable extends Closeable, AutoCloseable {

	@Override
	default void close(io.vertx.core.Completable<Void> completable) {
		rxClose().subscribe(completable::succeed, completable::fail);
	}

	/**
	 * Use {@link #close(io.vertx.core.Completable)}
	 */
	@Deprecated
	@Override
	default void close() {
		rxClose().blockingAwait();
	}

	Completable rxClose();
}
