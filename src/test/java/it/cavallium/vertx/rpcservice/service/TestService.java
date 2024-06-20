package it.cavallium.vertx.rpcservice.service;

import io.vertx.rxjava3.core.Vertx;
import it.cavallium.vertx.rpcservice.ServiceClient;
import it.cavallium.vertx.rpcservice.ServiceServer;
import it.cavallium.vertx.rpcservice.service.MathService.BooleanOperation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestService {

	@Test
	public void testService() {
		var v = Vertx.vertx();

		var svcImpl = new MathServiceImpl();

		try (var server = new ServiceServer<>(v, svcImpl, MathService.class)) {
			var client = new ServiceClient<>(v, MathService.class);
			var clientInstance = client.getInstance();
			Assertions.assertDoesNotThrow(clientInstance::hashCode);
			Assertions.assertDoesNotThrow(clientInstance::toString);
			Assertions.assertEquals("true", clientInstance.test());
			Assertions.assertFalse(clientInstance.calculateAnd(true, false).blockingGet());
			Assertions.assertTrue(clientInstance.calculateAnd(true, true).blockingGet());
			Assertions.assertTrue(clientInstance.calculateOr(true, false).blockingGet());
			Assertions.assertTrue(clientInstance.calculateOr(true, true).blockingGet());
			Assertions.assertFalse(clientInstance.calculateOr(false, false).blockingGet());
			Assertions.assertTrue(clientInstance.calculateNot(false).blockingGet());
			Assertions.assertFalse(clientInstance.calculateNot(true).blockingGet());
			Assertions.assertDoesNotThrow(() -> clientInstance.calculateCompletable().blockingAwait());
			Assertions.assertArrayEquals(new Boolean[] {false, false}, clientInstance.calculateMergeToArray(false, false).blockingGet());
			Assertions.assertEquals(new ArrayList<>(List.of(false, true)), new ArrayList<>(clientInstance.calculateMergeToList(false, true).blockingGet()));
			Assertions.assertTrue(clientInstance.calculateListOr(List.of(false, true)).blockingGet());
			Assertions.assertTrue(clientInstance.calculateArrayOr(new Boolean[] {false, true}).blockingGet());
			Assertions.assertTrue(clientInstance.calculateCustomRecordOr(new BooleanOperation(false, true)).blockingGet().result());
			Assertions.assertNull(clientInstance.calculateMaybe(false).blockingGet());
			Assertions.assertTrue(clientInstance.calculateMaybe(true).blockingGet(false));
		}
	}
}
