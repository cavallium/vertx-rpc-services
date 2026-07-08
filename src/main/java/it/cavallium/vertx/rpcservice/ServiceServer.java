package it.cavallium.vertx.rpcservice;

import static it.cavallium.vertx.rpcservice.ServiceClient.getReturnArity;
import static it.cavallium.vertx.rpcservice.ServiceUtils.getMethodEventBusAddress;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.exceptions.MissingBackpressureException;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.functions.Consumer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.MessageConsumerOptions;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.eventbus.Message;
import io.vertx.rxjava3.core.eventbus.MessageConsumer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import it.cavallium.vertx.rpcservice.ServiceMethodReturnValue.ServiceMethodReturnValueMessageCodec;
import it.cavallium.vertx.rpcservice.ServiceMethodRequest.ServiceMethodRequestMessageCodec;
import it.cavallium.vertx.rpcservice.ServiceStreamControl.ServiceStreamControlMessageCodec;
import it.cavallium.vertx.rpcservice.ServiceStreamMessage.ServiceStreamMessageMessageCodec;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class ServiceServer<T> implements RxCloseable {

	private static final int FLOWABLE_ITEM_BATCH_SIZE = 128;

	private final Class<? super T> serviceClass;
	private final Vertx vertx;
	private final boolean localOnly;
	private final List<MessageConsumer<ServiceMethodRequest>> consumers;
	private final Set<ServerStreamState> activeStreams;
	private static final ServiceMethodReturnValue<?> EMPTY_RESULT = new ServiceMethodReturnValue<>(null);

	public ServiceServer(Vertx vertx, T service, Class<? super T> serviceClass) {
		this(vertx, service, serviceClass, false);
	}

	public ServiceServer(Vertx vertx, T service, Class<? super T> serviceClass, boolean localOnly) {
		this.serviceClass = serviceClass;
		this.vertx = vertx;
		this.localOnly = localOnly;
		this.activeStreams = ConcurrentHashMap.newKeySet();
		ServiceUtils.tryRegisterDefaultCodec(vertx, ServiceMethodRequest.class, ServiceMethodRequestMessageCodec.INSTANCE);
		ServiceUtils.tryRegisterDefaultCodec(vertx, ServiceMethodReturnValue.class, ServiceMethodReturnValueMessageCodec.INSTANCE);
		ServiceUtils.tryRegisterDefaultCodec(vertx, ServiceStreamControl.class, ServiceStreamControlMessageCodec.INSTANCE);
		ServiceUtils.tryRegisterDefaultCodec(vertx, ServiceStreamMessage.class, ServiceStreamMessageMessageCodec.INSTANCE);

		if (!serviceClass.isInterface() && serviceClass.isAnnotationPresent(ServiceClass.class)) {
			throw new UnsupportedOperationException("Only interfaces are allowed");
		}

		record ServiceMethodDefinition(Method method, String address, Handler<Message<ServiceMethodRequest>> handler) {}

		this.consumers = Arrays.stream(serviceClass.getDeclaredMethods())
			.filter(method -> method.isAnnotationPresent(ServiceMethod.class))
			.map(method -> {
				var address = getMethodEventBusAddress(serviceClass, method);
				var handler = this.createRequestHandler(service, method);
				return new ServiceMethodDefinition(method, address, handler);
			})
			.map(definition -> {
				var consumerOptions = new MessageConsumerOptions().setAddress(definition.address).setLocalOnly(localOnly);
				return vertx.eventBus().consumer(consumerOptions, definition.handler);
			})
			.toList();
	}

	private Handler<Message<ServiceMethodRequest>> createRequestHandler(T service, Method declaredMethod) {
		var lookup = MethodHandles.publicLookup();
		MethodHandle mh;
		int paramsCount;
		try {
			mh = lookup.unreflect(declaredMethod).bindTo(service);
			paramsCount = declaredMethod.getParameterCount();
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
		var arity = getReturnArity(serviceClass, declaredMethod);
		var registrationTimeoutMs = Math.max(1L, declaredMethod.getAnnotation(ServiceMethod.class).timeout()) * 1000L;
		return msg -> {
			try {
				var req = msg.body();

				if (req.arguments() == null && paramsCount > 0) {
					msg.fail(500, "Arguments array is null, expected " + paramsCount + " arguments");
				}

				if (req.arguments() != null) {
					var genericParameterTypes = declaredMethod.getGenericParameterTypes();
					for (int i = 0; i < req.arguments().length; i++) {
						var arg = req.arguments()[i];
						var parameterType = genericParameterTypes[i];
						req.arguments()[i] = ServiceUtils.castToType(true, parameterType, arg);
					}
				}

				switch (arity) {
					case COMPLETABLE -> ((Completable) mh.invokeWithArguments(req.arguments()))
						.subscribe(getEmptyReplyHandler(msg), getErrorHandler(msg));
					case FLOWABLE -> openFlowableStream(
						msg,
						(Flowable<?>) mh.invokeWithArguments(req.arguments()),
						registrationTimeoutMs
					);
					case MAYBE -> ((Maybe<?>) mh.invokeWithArguments(req.arguments()))
						.subscribe(getReplyHandler(msg), getErrorHandler(msg), getEmptyReplyHandler(msg));
					case SINGLE -> ((Single<?>) mh.invokeWithArguments(req.arguments()))
						.subscribe(getReplyHandler(msg), getErrorHandler(msg));
				}
			} catch (Throwable e) {
				msg.fail(500, formatError(e));
			}
		};
	}

	private void openFlowableStream(Message<ServiceMethodRequest> msg,
		Flowable<?> source,
		long registrationTimeoutMs) {
		var controlAddress = ServiceUtils.newStreamAddress("c.");
		var state = new ServerStreamState(controlAddress, registrationTimeoutMs);
		activeStreams.add(state);
		state.open(source, msg);
	}

	private static @NotNull Consumer<Object> getReplyHandler(Message<ServiceMethodRequest> msg) {
		return ok -> msg.reply(new ServiceMethodReturnValue<>(ok));
	}

	private static @NotNull Consumer<Throwable> getErrorHandler(Message<ServiceMethodRequest> msg) {
		return err -> msg.fail(500, formatError(err));
	}

	private static @NotNull Action getEmptyReplyHandler(Message<ServiceMethodRequest> msg) {
		return () -> msg.reply(EMPTY_RESULT);
	}

	static String formatError(Throwable e) {
		var sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}

	@Override
	public Completable rxClose() {
		return Flowable.fromIterable(List.copyOf(activeStreams))
			.flatMapCompletable(stream -> stream.closeAsync(true))
			.andThen(Flowable.fromIterable(consumers)
				.flatMapCompletable(MessageConsumer::unregister));
	}

	private static void addRequested(AtomicLong requested, long n) {
		while (true) {
			var current = requested.get();
			var next = addCap(current, n);
			if (requested.compareAndSet(current, next)) {
				return;
			}
		}
	}

	private static long addCap(long a, long b) {
		var result = a + b;
		return result < 0L ? Long.MAX_VALUE : result;
	}

	private final class ServerStreamState implements Subscriber<Object> {

		private final String controlAddress;
		private final DeliveryOptions streamDeliveryOptions;
		private final long registrationTimeoutMs;
		private final AtomicReference<Subscription> upstream;
		private final AtomicLong requested;
		private final AtomicLong pendingRequested;
		private final AtomicBoolean closed;
		private final Object terminalLock;
		private volatile MessageConsumer<ServiceStreamControl> controlConsumer;
		private String responseAddress;
		private ServiceStreamMessage<?> pendingTerminal;
		private ArrayList<Object> pendingItems;
		private Thread batchingRequestThread;
		private boolean terminal;
		private boolean batchingRequestFirstItemSent;
		private int batchingRequestDepth;
		private long registrationTimerId;
		private long terminalCloseTimerId;

		private ServerStreamState(String controlAddress, long registrationTimeoutMs) {
			this.controlAddress = controlAddress;
			this.streamDeliveryOptions = new DeliveryOptions().setLocalOnly(localOnly);
			this.registrationTimeoutMs = registrationTimeoutMs;
			this.upstream = new AtomicReference<>();
			this.requested = new AtomicLong();
			this.pendingRequested = new AtomicLong();
			this.closed = new AtomicBoolean();
			this.terminalLock = new Object();
			this.registrationTimerId = -1L;
			this.terminalCloseTimerId = -1L;
		}

		private void open(Flowable<?> source, Message<ServiceMethodRequest> initialMessage) {
			var consumerOptions = new MessageConsumerOptions()
				.setAddress(controlAddress)
				.setLocalOnly(localOnly);
			controlConsumer = vertx.eventBus().consumer(consumerOptions, this::handleControl);
			controlConsumer.completion().subscribe(() -> {
				if (closed.get()) {
					controlConsumer.unregister().subscribe(() -> {
					}, ignored -> {
					});
					return;
				}
				try {
					startRegistrationTimeout();
					source.subscribe(this);
					initialMessage.reply(new ServiceMethodReturnValue<>(controlAddress));
				} catch (Throwable err) {
					close();
					initialMessage.fail(500, formatError(err));
				}
			}, err -> {
				activeStreams.remove(this);
				initialMessage.fail(500, formatError(err));
			});
		}

		private void handleControl(Message<ServiceStreamControl> msg) {
			var control = msg.body();
			if (closed.get()) {
				msg.fail(410, "Flowable stream is closed");
				return;
			}
			if (control.cancel()) {
				msg.reply(Boolean.TRUE);
				close();
				return;
			}
			if (control.request() < 0L) {
				msg.fail(400, "Flowable stream request amount must not be negative");
				failStream(new IllegalArgumentException("Flowable stream request amount must not be negative"));
				return;
			}
			ServiceStreamMessage<?> terminalToSend;
			String terminalAddress;
			try {
				if (control.responseAddress() != null) {
					terminalToSend = registerResponseAddress(control.responseAddress());
				} else if (!hasResponseAddress()) {
					msg.fail(400, "Flowable stream response address is missing");
					close();
					return;
				} else {
					terminalToSend = null;
				}
				terminalAddress = getResponseAddress();
			} catch (Throwable err) {
				msg.fail(400, formatError(err));
				failStream(err);
				return;
			}
			if (terminalToSend == null && control.request() > 0L && !isTerminal()) {
				requestUpstream(control.request());
			}
			msg.reply(Boolean.TRUE);
			if (terminalToSend != null) {
				sendTerminalAndClose(terminalAddress, terminalToSend);
			}
		}

		private ServiceStreamMessage<?> registerResponseAddress(String newResponseAddress) {
			if (newResponseAddress.isBlank()) {
				throw new IllegalArgumentException("Flowable stream response address is blank");
			}
			ServiceStreamMessage<?> terminalToSend;
			synchronized (terminalLock) {
				if (responseAddress == null) {
					responseAddress = newResponseAddress;
				} else if (!responseAddress.equals(newResponseAddress)) {
					throw new IllegalStateException("Flowable stream response address changed");
				}
				terminalToSend = pendingTerminal;
				pendingTerminal = null;
			}
			clearRegistrationTimeout();
			return terminalToSend;
		}

		private boolean hasResponseAddress() {
			synchronized (terminalLock) {
				return responseAddress != null;
			}
		}

		private void requestUpstream(long n) {
			addRequested(requested, n);
			var subscription = upstream.get();
			if (subscription != null) {
				requestFromSubscription(subscription, n);
				return;
			}
			addRequested(pendingRequested, n);
			subscription = upstream.get();
			if (subscription != null) {
				var pending = pendingRequested.getAndSet(0L);
				if (pending > 0L) {
					requestFromSubscription(subscription, pending);
				}
			}
		}

		@Override
		public void onSubscribe(Subscription subscription) {
			if (!upstream.compareAndSet(null, subscription)) {
				subscription.cancel();
				return;
			}
			if (closed.get()) {
				subscription.cancel();
				return;
			}
			var pending = pendingRequested.getAndSet(0L);
			if (pending > 0L) {
				requestFromSubscription(subscription, pending);
			}
		}

		@Override
		public void onNext(Object item) {
			Throwable failure = null;
			synchronized (terminalLock) {
				if (closed.get() || terminal) {
					return;
				}
				if (responseAddress == null) {
					failure = new MissingBackpressureException("Remote Flowable emitted before the client registered a response address");
				} else if (!tryConsumeRequested()) {
					failure = new MissingBackpressureException("Remote Flowable emitted more items than requested");
				} else if (!isBatchingRequestThread()) {
					flushPendingItemsLocked();
					sendStreamMessageLocked(responseAddress, ServiceStreamMessage.next(item));
				} else if (!batchingRequestFirstItemSent) {
					batchingRequestFirstItemSent = true;
					sendStreamMessageLocked(responseAddress, ServiceStreamMessage.next(item));
				} else {
					if (pendingItems == null) {
						pendingItems = new ArrayList<>(Math.min(FLOWABLE_ITEM_BATCH_SIZE, 16));
					}
					pendingItems.add(item);
					if (pendingItems.size() >= FLOWABLE_ITEM_BATCH_SIZE) {
						flushPendingItemsLocked();
					}
				}
			}
			if (failure != null) {
				failStream(failure);
				return;
			}
		}

		@Override
		public void onError(Throwable err) {
			sendOrStoreTerminal(ServiceStreamMessage.error(500, formatError(err)));
		}

		@Override
		public void onComplete() {
			sendOrStoreTerminal(ServiceStreamMessage.complete());
		}

		private boolean tryConsumeRequested() {
			while (true) {
				var current = requested.get();
				if (current <= 0L) {
					return false;
				}
				if (current == Long.MAX_VALUE) {
					return true;
				}
				if (requested.compareAndSet(current, current - 1L)) {
					return true;
				}
			}
		}

		private String getResponseAddress() {
			synchronized (terminalLock) {
				return responseAddress;
			}
		}

		private boolean isTerminal() {
			synchronized (terminalLock) {
				return terminal;
			}
		}

		private void sendOrStoreTerminal(ServiceStreamMessage<?> terminalMessage) {
			boolean scheduleClose;
			synchronized (terminalLock) {
				if (terminal || closed.get()) {
					return;
				}
				terminal = true;
				if (responseAddress == null) {
					pendingTerminal = terminalMessage;
					return;
				}
				flushPendingItemsLocked();
				sendStreamMessageLocked(responseAddress, terminalMessage);
				scheduleClose = true;
			}
			if (scheduleClose) {
				scheduleTerminalClose();
			}
		}

		private void sendTerminalAndClose(String targetAddress, ServiceStreamMessage<?> terminalMessage) {
			vertx.eventBus().send(targetAddress, terminalMessage, streamDeliveryOptions);
			scheduleTerminalClose();
		}

		private void flushPendingItemsLocked() {
			if (pendingItems == null || pendingItems.isEmpty() || responseAddress == null) {
				return;
			}
			var items = pendingItems;
			pendingItems = null;
			var message = items.size() == 1
				? ServiceStreamMessage.next(items.getFirst())
				: ServiceStreamMessage.nextBatch(items);
			sendStreamMessageLocked(responseAddress, message);
		}

		private void sendStreamMessageLocked(String targetAddress, ServiceStreamMessage<?> message) {
			vertx.eventBus().send(targetAddress, message, streamDeliveryOptions);
		}

		private void requestFromSubscription(Subscription subscription, long n) {
			var batching = beginBatchingRequest();
			try {
				subscription.request(n);
			} finally {
				endBatchingRequest(batching);
			}
		}

		private boolean beginBatchingRequest() {
			synchronized (terminalLock) {
				var currentThread = Thread.currentThread();
				if (batchingRequestDepth == 0) {
					batchingRequestThread = currentThread;
					batchingRequestFirstItemSent = false;
					batchingRequestDepth = 1;
					return true;
				}
				if (batchingRequestThread == currentThread) {
					batchingRequestDepth++;
					return true;
				}
				return false;
			}
		}

		private void endBatchingRequest(boolean batching) {
			if (!batching) {
				return;
			}
			synchronized (terminalLock) {
				if (batchingRequestThread != Thread.currentThread() || batchingRequestDepth <= 0) {
					return;
				}
				batchingRequestDepth--;
				if (batchingRequestDepth == 0) {
					flushPendingItemsLocked();
					batchingRequestThread = null;
					batchingRequestFirstItemSent = false;
				}
			}
		}

		private boolean isBatchingRequestThread() {
			return batchingRequestDepth > 0 && batchingRequestThread == Thread.currentThread();
		}

		private void failStream(Throwable err) {
			var subscription = upstream.get();
			if (subscription != null) {
				subscription.cancel();
			}
			sendOrStoreTerminal(ServiceStreamMessage.error(500, formatError(err)));
		}

		private void startRegistrationTimeout() {
			if (registrationTimeoutMs <= 0L) {
				return;
			}
			registrationTimerId = vertx.setTimer(registrationTimeoutMs, ignored -> {
				synchronized (terminalLock) {
					if (responseAddress != null || closed.get()) {
						return;
					}
				}
				close();
			});
		}

		private void clearRegistrationTimeout() {
			var timerId = registrationTimerId;
			if (timerId != -1L) {
				registrationTimerId = -1L;
				vertx.cancelTimer(timerId);
			}
		}

		private void scheduleTerminalClose() {
			if (terminalCloseTimerId != -1L || closed.get()) {
				return;
			}
			terminalCloseTimerId = vertx.setTimer(1000L, ignored -> close());
		}

		private void clearTerminalCloseTimeout() {
			var timerId = terminalCloseTimerId;
			if (timerId != -1L) {
				terminalCloseTimerId = -1L;
				vertx.cancelTimer(timerId);
			}
		}

		private void close() {
			closeAsync(false).subscribe(() -> {
			}, ignored -> {
			});
		}

		private Completable closeAsync(boolean notifyClient) {
			if (notifyClient) {
				sendShutdownTerminal();
			}
			var consumer = closeState();
			if (consumer != null && consumer.isRegistered()) {
				return consumer.unregister().onErrorComplete();
			}
			return Completable.complete();
		}

		private void sendShutdownTerminal() {
			synchronized (terminalLock) {
				if (closed.get() || terminal || responseAddress == null) {
					return;
				}
				terminal = true;
				flushPendingItemsLocked();
				sendStreamMessageLocked(responseAddress,
					ServiceStreamMessage.error(500, "Flowable stream closed by service shutdown"));
			}
		}

		private MessageConsumer<ServiceStreamControl> closeState() {
			if (!closed.compareAndSet(false, true)) {
				return null;
			}
			clearRegistrationTimeout();
			clearTerminalCloseTimeout();
			synchronized (terminalLock) {
				pendingItems = null;
				batchingRequestThread = null;
				batchingRequestFirstItemSent = false;
				batchingRequestDepth = 0;
			}
			var subscription = upstream.getAndSet(null);
			if (subscription != null) {
				subscription.cancel();
			}
			activeStreams.remove(this);
			return controlConsumer;
		}
	}
}
