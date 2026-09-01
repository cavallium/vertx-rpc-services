package it.cavallium.vertx.rpcservice.service;

import io.reactivex.rxjava3.core.Single;
import io.vertx.core.Handler;
import io.vertx.core.VertxOptions;
import io.vertx.core.eventbus.DeliveryContext;
import io.vertx.core.eventbus.Message;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.core.spi.metrics.EventBusMetrics;
import io.vertx.core.spi.metrics.VertxMetrics;
import io.vertx.rxjava3.core.Vertx;
import it.cavallium.vertx.rpcservice.RemoteServiceException;
import it.cavallium.vertx.rpcservice.ServiceClient;
import it.cavallium.vertx.rpcservice.ServiceServer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ServiceFlowableTest {

	@Test
	public void streamsSmallAndLargeRanges() {
		try (var fixture = new RpcFixture()) {
			Assertions.assertEquals(List.of(4, 5, 6), fixture.client.calculateRange(4, 3).toList().blockingGet());

			var large = fixture.client.calculateRange(0, 10_000)
				.reduce(0L, Long::sum)
				.blockingGet();
			Assertions.assertEquals(49_995_000L, large);
		}
	}

	@Test
	public void respectsIncrementalBackpressure() {
		try (var fixture = new RpcFixture()) {
			var backpressure = fixture.client.calculateBackpressuredRange(10).test(0L);
			waitUntil(fixture.service::backpressureSubscribed);
			Assertions.assertEquals(0L, fixture.service.backpressureRequests());

			backpressure.request(1L);
			backpressure.awaitCount(1).assertValues(0).assertNotComplete();
			waitUntil(() -> fixture.service.backpressureRequests() == 1L);

			backpressure.request(2L);
			backpressure.awaitCount(3).assertValues(0, 1, 2).assertNotComplete();
			waitUntil(() -> fixture.service.backpressureRequests() == 3L);

			backpressure.request(7L);
			backpressure.awaitDone(5, TimeUnit.SECONDS)
				.assertValues(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
				.assertNoErrors()
				.assertComplete();
			waitUntil(() -> fixture.service.backpressureRequests() == 10L);
		}
	}

	@Test
	public void unboundedDownstreamDemandKeepsBoundedUpstreamWindow() {
		try (var fixture = new RpcFixture()) {
			var sum = fixture.client.calculateTrackedWindowRange(10_000)
				.reduce(0L, Long::sum)
				.blockingGet();
			Assertions.assertEquals(49_995_000L, sum);
			Assertions.assertTrue(fixture.service.trackedWindowMaxOutstanding() <= 128L,
				"Upstream requested window must stay bounded, max was " + fixture.service.trackedWindowMaxOutstanding());
		}
	}

	@Test
	public void completesEmptyFlowableWithoutItemDemand() {
		try (var fixture = new RpcFixture()) {
			fixture.client.calculateEmptyFlowable()
				.test(0L)
				.awaitDone(5, TimeUnit.SECONDS)
				.assertNoValues()
				.assertNoErrors()
				.assertComplete();
		}
	}

	@Test
	public void propagatesErrorsBeforeAndAfterItems() {
		try (var fixture = new RpcFixture()) {
			fixture.client.failWithNestedThrowableFlowable()
				.test(0L)
				.awaitDone(5, TimeUnit.SECONDS)
				.assertNoValues()
				.assertError(err -> err instanceof RemoteServiceException
					&& err.getMessage().contains("java.lang.IllegalArgumentException: top-level marker")
					&& err.getMessage().contains("Suppressed: java.lang.UnsupportedOperationException: suppressed marker")
					&& err.getMessage().contains("Caused by: java.lang.IllegalStateException: root cause marker"));

			var afterItems = fixture.client.failFlowableAfterItems(2).test(0L);
			afterItems.request(2L);
			afterItems.awaitDone(5, TimeUnit.SECONDS)
				.assertValues(0, 1)
				.assertError(err -> err instanceof RemoteServiceException
					&& err.getMessage().contains("java.lang.IllegalArgumentException: stream failure marker"));
		}
	}

	@Test
	public void cancelsRemoteSubscriptionOnClientCancel() {
		try (var fixture = new RpcFixture()) {
			var never = fixture.client.calculateNeverFlowable().test(0L);
			waitUntil(fixture.service::neverFlowableSubscribed);
			never.cancel();
			waitUntil(fixture.service::neverFlowableCancelled);
		}
	}

	@Test
	public void firstElementCancellationKeepsResponseConsumerUntilRemoteTerminalSettles() {
		var metrics = new CountingEventBusMetrics();
		try (var fixture = new RpcFixture(metrics)) {
			Assertions.assertEquals(42,
				fixture.client.calculateRange(42, 1).firstElement().blockingGet());

			waitUntil(() -> metrics.streamResponseSettlements() >= 2);
			Assertions.assertEquals(0, metrics.streamResponseDiscards(),
				"A terminal already in flight must not reach an unregistered response consumer");
			Assertions.assertEquals(2, metrics.streamResponseDeliveries(),
				"The first item and the late terminal must both settle before consumer teardown");
		}
	}

	@Test
	public void invalidRequestFailsAndCancelsRemoteSubscription() {
		try (var fixture = new RpcFixture()) {
			var never = fixture.client.calculateNeverFlowable().test(0L);
			waitUntil(fixture.service::neverFlowableSubscribed);
			never.request(-1L);
			never.awaitDone(5, TimeUnit.SECONDS)
				.assertNoValues()
				.assertError(IllegalArgumentException.class);
			waitUntil(fixture.service::neverFlowableCancelled);
		}
	}

	@Test
	public void serverCloseTerminatesActiveFlowableClients() {
		var fixture = new RpcFixture();
		try {
			var never = fixture.client.calculateNeverFlowable().test(0L);
			waitUntil(fixture.service::neverFlowableSubscribed);
			never.request(1L);
			waitUntil(() -> fixture.service.neverFlowableRequests() == 1L);

			fixture.closeServer();

			never.awaitDone(5, TimeUnit.SECONDS)
				.assertNoValues()
				.assertError(err -> err instanceof RemoteServiceException
					&& err.getMessage().contains("Flowable stream closed by service shutdown"));
			waitUntil(fixture.service::neverFlowableCancelled);
		} finally {
			fixture.close();
		}
	}

	@Test
	public void flowableCallsAreColdAndIndependent() {
		try (var fixture = new RpcFixture()) {
			var before = fixture.service.rangeSubscriptions();
			var flowable = fixture.client.calculateRange(10, 4);

			Assertions.assertEquals(List.of(10, 11, 12, 13), flowable.toList().blockingGet());
			Assertions.assertEquals(List.of(10, 11, 12, 13), flowable.toList().blockingGet());
			waitUntil(() -> fixture.service.rangeSubscriptions() == before + 2L);
		}
	}

	@Test
	public void supportsConcurrentFlowableSubscriptions() {
		try (var fixture = new RpcFixture()) {
			var calls = IntStream.range(0, 16)
				.mapToObj(i -> fixture.client.calculateRange(i * 10, 10).toList())
				.toList();
			var result = Single.zip(calls, values -> Arrays.stream(values)
				.map(value -> new ArrayList<>((List<?>) value))
				.toList()).blockingGet();

			for (int i = 0; i < result.size(); i++) {
				Assertions.assertEquals(IntStream.range(i * 10, i * 10 + 10).boxed().toList(), result.get(i));
			}
		}
	}

	@Test
	public void convertsRootByteArrayItems() {
		try (var fixture = new RpcFixture()) {
			var input = new byte[] {1, 2, 3, 4, 5};
			var output = fixture.client.calculateBytesFlowable(input).toList().blockingGet().getFirst();
			Assertions.assertArrayEquals(input, output);
		}
	}

	@Test
	public void batchesReadyItemsIntoFewerTransportMessages() {
		try (var fixture = new RpcFixture()) {
			var stats = new StreamMessageStats();
			fixture.vertx.getDelegate().eventBus().addOutboundInterceptor(stats);
			try {
				var sum = fixture.client.calculateRange(0, 1024)
					.reduce(0L, Long::sum)
					.blockingGet();

				Assertions.assertEquals(523_776L, sum);
				Assertions.assertTrue(stats.batchMessages() > 0,
					"Expected at least one batched Flowable transport message");
				Assertions.assertTrue(stats.streamMessages() < 128,
					"Expected batching to keep stream transport messages low, got " + stats.streamMessages());
			} finally {
				fixture.vertx.getDelegate().eventBus().removeOutboundInterceptor(stats);
			}
		}
	}

	@Test
	public void doesNotBatchSingleReadyItem() {
		try (var fixture = new RpcFixture()) {
			var stats = new StreamMessageStats();
			fixture.vertx.getDelegate().eventBus().addOutboundInterceptor(stats);
			try {
				Assertions.assertEquals(List.of(42), fixture.client.calculateRange(42, 1).toList().blockingGet());
				Assertions.assertEquals(0, stats.batchMessages());
			} finally {
				fixture.vertx.getDelegate().eventBus().removeOutboundInterceptor(stats);
			}
		}
	}

	private static void waitUntil(BooleanSupplier condition) {
		var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(10L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				Assertions.fail(e);
			}
		}
		Assertions.fail("Timed out waiting for condition");
	}

	private static final class StreamMessageStats implements Handler<DeliveryContext<Object>> {

		private static final String STREAM_MESSAGE_CLASS = "it.cavallium.vertx.rpcservice.ServiceStreamMessage";

		private final AtomicInteger streamMessages = new AtomicInteger();
		private final AtomicInteger batchMessages = new AtomicInteger();

		@Override
		public void handle(DeliveryContext<Object> context) {
			var body = context.body();
			if (body != null && STREAM_MESSAGE_CLASS.equals(body.getClass().getName())) {
				streamMessages.incrementAndGet();
				if (body.toString().contains("kind=NEXT_BATCH")) {
					batchMessages.incrementAndGet();
				}
			}
			context.next();
		}

		private int streamMessages() {
			return streamMessages.get();
		}

		private int batchMessages() {
			return batchMessages.get();
		}
	}

	private static final class CountingEventBusMetrics implements EventBusMetrics<String> {

		private final AtomicInteger streamResponseDeliveries = new AtomicInteger();
		private final AtomicInteger streamResponseDiscards = new AtomicInteger();

		@Override
		public String handlerRegistered(String address) {
			return address;
		}

		@Override
		public void messageDelivered(String address, boolean local) {
			if (isStreamResponse(address)) streamResponseDeliveries.incrementAndGet();
		}

		@Override
		public void discardMessage(String address, boolean local, Message<?> message) {
			if (isStreamResponse(address)) streamResponseDiscards.incrementAndGet();
		}

		private int streamResponseDeliveries() {
			return streamResponseDeliveries.get();
		}

		private int streamResponseDiscards() {
			return streamResponseDiscards.get();
		}

		private int streamResponseSettlements() {
			return streamResponseDeliveries() + streamResponseDiscards();
		}

		private static boolean isStreamResponse(String address) {
			return address != null && address.startsWith("r.");
		}
	}

	private static final class RpcFixture implements AutoCloseable {

		private final Vertx vertx;
		private final MathServiceImpl service;
		private final ServiceServer<MathService> server;
		private final MathService client;
		private boolean serverClosed;

		private RpcFixture() {
			this(null);
		}

		private RpcFixture(CountingEventBusMetrics eventBusMetrics) {
			this.vertx = eventBusMetrics == null
				? Vertx.vertx()
				: Vertx.newInstance(io.vertx.core.Vertx.builder()
					.with(new VertxOptions().setMetricsOptions(
						new MetricsOptions().setEnabled(true)))
					.withMetrics(options -> new VertxMetrics() {
						@Override
						public EventBusMetrics<?> createEventBusMetrics() {
							return eventBusMetrics;
						}

						@Override
						public boolean isMetricsEnabled() {
							return true;
						}
					})
					.build());
			this.service = new MathServiceImpl();
			this.server = new ServiceServer<>(vertx, service, MathService.class);
			this.client = new ServiceClient<>(vertx, MathService.class).getInstance();
		}

		private void closeServer() {
			if (!serverClosed) {
				serverClosed = true;
				server.rxClose().blockingAwait();
			}
		}

		@Override
		public void close() {
			try {
				closeServer();
			} finally {
				vertx.close().blockingAwait();
			}
		}
	}
}
