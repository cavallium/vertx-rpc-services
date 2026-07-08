package it.cavallium.vertx.rpcservice.service;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import it.cavallium.vertx.rpcservice.ServiceClass;
import it.cavallium.vertx.rpcservice.ServiceClient;
import it.cavallium.vertx.rpcservice.ServiceMethod;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ServiceDefinitionValidationTest {

	@Test
	public void rejectsRawFlowableReturnTypes() {
		var vertx = Vertx.vertx();
		try {
			var error = Assertions.assertThrows(UnsupportedOperationException.class,
				() -> new ServiceClient<>(vertx, RawFlowableService.class));
			Assertions.assertTrue(error.getMessage().contains("Flowable<?>"));
		} finally {
			vertx.close().blockingAwait();
		}
	}

	@Test
	public void rejectsUnsupportedReturnTypes() {
		var vertx = Vertx.vertx();
		try {
			var error = Assertions.assertThrows(UnsupportedOperationException.class,
				() -> new ServiceClient<>(vertx, UnsupportedReturnService.class));
			Assertions.assertTrue(error.getMessage().contains("Flowable<?>"));
		} finally {
			vertx.close().blockingAwait();
		}
	}

	@Test
	public void acceptsParameterizedFlowableReturnTypes() {
		var vertx = Vertx.vertx();
		try {
			Assertions.assertDoesNotThrow(() -> new ServiceClient<>(vertx, ValidFlowableService.class));
		} finally {
			vertx.close().blockingAwait();
		}
	}

	@ServiceClass
	interface RawFlowableService {

		@SuppressWarnings("rawtypes")
		@ServiceMethod
		Flowable values();
	}

	@ServiceClass
	interface UnsupportedReturnService {

		@ServiceMethod
		String value();
	}

	@ServiceClass
	interface ValidFlowableService {

		@ServiceMethod
		Flowable<Integer> values();

		@ServiceMethod
		Single<Integer> unary();
	}
}
