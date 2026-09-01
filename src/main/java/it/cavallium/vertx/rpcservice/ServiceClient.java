package it.cavallium.vertx.rpcservice;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.MultiMap;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.MessageConsumerOptions;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.eventbus.Message;
import io.vertx.rxjava3.core.eventbus.MessageConsumer;
import it.cavallium.vertx.rpcservice.ServiceMethodRequest.ServiceMethodRequestMessageCodec;
import it.cavallium.vertx.rpcservice.ServiceMethodReturnValue.ServiceMethodReturnValueMessageCodec;
import it.cavallium.vertx.rpcservice.ServiceStreamControl.ServiceStreamControlMessageCodec;
import it.cavallium.vertx.rpcservice.ServiceStreamMessage.ServiceStreamMessageMessageCodec;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class ServiceClient<T> {

	private static final long FLOWABLE_REMOTE_WINDOW = 128L;
	private static final long FLOWABLE_REMOTE_REQUEST_BATCH = 128L;

	private final Vertx vertx;
	private final boolean localOnly;
	private final T instance;

	enum ReturnArity {
		COMPLETABLE,
		FLOWABLE,
		MAYBE,
		SINGLE
	}

	private record MethodData(String address, Type returnType, ReturnArity arity, int timeout) {}

	public ServiceClient(Vertx vertx, Class<T> serviceClass) {
		this(vertx, serviceClass, false);
	}

	@SuppressWarnings("unchecked")
	public ServiceClient(Vertx vertx, Class<T> serviceClass, boolean localOnly) {
		this.vertx = vertx;
		this.localOnly = localOnly;
		ServiceUtils.tryRegisterDefaultCodec(vertx, ServiceMethodRequest.class, ServiceMethodRequestMessageCodec.INSTANCE);
		ServiceUtils.tryRegisterDefaultCodec(vertx, ServiceMethodReturnValue.class, ServiceMethodReturnValueMessageCodec.INSTANCE);
		ServiceUtils.tryRegisterDefaultCodec(vertx, ServiceStreamControl.class, ServiceStreamControlMessageCodec.INSTANCE);
		ServiceUtils.tryRegisterDefaultCodec(vertx, ServiceStreamMessage.class, ServiceStreamMessageMessageCodec.INSTANCE);

		if (!serviceClass.isInterface() && serviceClass.isAnnotationPresent(ServiceClass.class)) {
			throw new UnsupportedOperationException("Only interfaces are allowed");
		}

		Map<Method, MethodData> methodData = processMethods(serviceClass, serviceClass.getDeclaredMethods());
		this.instance = (T) Proxy.newProxyInstance(this.getClass().getClassLoader(),
			new Class[]{serviceClass},
			new DynamicInvocationHandler(serviceClass, methodData)
		);
	}

	private Map<Method, MethodData> processMethods(Class<T> serviceClass, Method[] declaredMethods) {
		return Arrays
			.stream(declaredMethods)
			.filter(method -> method.isAnnotationPresent(ServiceMethod.class))
			.filter(method -> !method.isDefault())
			.collect(Collectors.toMap(Function.identity(), method -> {
				var annotation = method.getAnnotation(ServiceMethod.class);
				String address = ServiceUtils.getMethodEventBusAddress(serviceClass, method);
				final ReturnArity arity = getReturnArity(serviceClass, method);
				if (arity == ReturnArity.COMPLETABLE) {
					return new MethodData(address, null, ReturnArity.COMPLETABLE, annotation.timeout());
				} else {
					Type returnType = method.getGenericReturnType();
					if (returnType instanceof ParameterizedType parameterizedType) {
						Type[] typeArguments = parameterizedType.getActualTypeArguments();
						if (typeArguments.length != 1) {
							throw new UnsupportedOperationException(
								"Method return type is not valid for service \"" + serviceClass + "\", method \"" + method
									+ "\", it should be Single<?>, Maybe<?>, or Flowable<?> with a single type parameter");
						}
						var returnTypeInner = typeArguments[0];
						return new MethodData(address, returnTypeInner, arity, annotation.timeout());
					} else {
						throw new UnsupportedOperationException(
							"Method return type is not valid for service \"" + serviceClass + "\", method \"" + method
								+ "\", it should be Single<?>, Maybe<?>, or Flowable<?> with a valid type parameter");
					}
				}
			}));
	}

	static <T> @NotNull ReturnArity getReturnArity(Class<T> serviceClass, Method method) {
		Class<?> returnTypeClass = method.getReturnType();
		ReturnArity arity;
		if (returnTypeClass.equals(Completable.class)) {
			arity = ReturnArity.COMPLETABLE;
		} else if (returnTypeClass.equals(Flowable.class)) {
			arity = ReturnArity.FLOWABLE;
		} else if (returnTypeClass.equals(Maybe.class)) {
			arity = ReturnArity.MAYBE;
		} else if (returnTypeClass.equals(Single.class)) {
			arity = ReturnArity.SINGLE;
		} else {
			throw new UnsupportedOperationException(
				"Method return type is not valid for service \"" + serviceClass + "\", method \"" + method
					+ "\", it should be Single<?>, Maybe<?>, Flowable<?>, or Completable");
		}
		return arity;
	}

	private static Throwable mapReplyException(Throwable err) {
		if (err instanceof ReplyException re) {
			return new RemoteServiceException(re.failureCode(), re.getMessage(), re.getMessage());
		}
		return err;
	}

	private static <U> Single<U> mapReplyExceptionSingle(Throwable err) {
		return Single.error(mapReplyException(err));
	}

	private static long addCap(long a, long b) {
		var result = a + b;
		return result < 0L ? Long.MAX_VALUE : result;
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

	private class DynamicInvocationHandler implements InvocationHandler {

		private final Class<T> serviceClass;
		private final Map<Method, ServiceClient.MethodData> methodDataMap;
		private final Map<Method, DeliveryOptions> methodDeliveryOptionsMap;
		private final Object object;


		public DynamicInvocationHandler(Class<T> serviceClass, Map<Method, MethodData> methodDataMap) {
			this.serviceClass = serviceClass;
			this.methodDataMap = methodDataMap;
			this.methodDeliveryOptionsMap = methodDataMap.entrySet()
					.stream()
					.collect(Collectors.toMap(Map.Entry::getKey, e -> new DeliveryOptions()
														.setLocalOnly(localOnly)
                            .setSendTimeout(e.getValue().timeout() * 1000L)));
			this.object = new Object();
		}

		@SuppressWarnings("ReactiveStreamsUnusedPublisher")
		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (!method.isAnnotationPresent(ServiceMethod.class)) {
				if (method.getDeclaringClass() == Object.class) {
					return method.invoke(object, args);
				} else if (method.isDefault()) {
					try {
						return InvocationHandler.invokeDefault(proxy, method, args);
					} catch (Throwable e) {
						throw new RuntimeException(e);
					}
				} else {
					throw new UnsupportedOperationException("Method \"" + method + "\" is not annotated with @ServiceMethod!");
				}
			}
			var methodData = methodDataMap.get(method);
			var deliveryOptions = methodDeliveryOptionsMap.get(method);
			var address = methodData.address;
			var request = new ServiceMethodRequest(args);
			var requestSingle = Single.defer(() -> vertx.eventBus().<ServiceMethodReturnValue<?>>request(address, request, deliveryOptions));

			var returnType = methodData.returnType();
			var errorMappedSingle = requestSingle.onErrorResumeNext(ServiceClient::mapReplyExceptionSingle);

			return switch (methodData.arity) {
				case COMPLETABLE -> errorMappedSingle.ignoreElement();
				case FLOWABLE -> new RemoteFlowable<>(address, request, deliveryOptions, returnType);
				case MAYBE -> errorMappedSingle.mapOptional(msg -> {
					var value = msg.body().value();
					return Optional.ofNullable(ServiceUtils.castToType(true, returnType, value));
				});
				case SINGLE -> errorMappedSingle.map(msg -> {
					var value = msg.body().value();
					return Objects.requireNonNull(ServiceUtils.castToType(true, returnType, value));
				});
			};
		}
	}

	private final class RemoteFlowable<R> extends Flowable<R> {

		private final String address;
		private final ServiceMethodRequest request;
		private final DeliveryOptions deliveryOptions;
		private final Type itemType;

		private RemoteFlowable(String address, ServiceMethodRequest request, DeliveryOptions deliveryOptions, Type itemType) {
			this.address = address;
			this.request = request;
			this.deliveryOptions = deliveryOptions;
			this.itemType = itemType;
		}

		@Override
		protected void subscribeActual(Subscriber<? super R> subscriber) {
			var subscription = new RemoteFlowableSubscription<>(subscriber, address, request, deliveryOptions, itemType);
			subscriber.onSubscribe(subscription);
			subscription.open();
		}
	}

	private final class RemoteFlowableSubscription<R> implements Subscription {

		private final Subscriber<? super R> downstream;
		private final String address;
		private final ServiceMethodRequest request;
		private final DeliveryOptions deliveryOptions;
		private final Type itemType;
		private final String responseAddress;
		private final AtomicLong requested;
		private final AtomicLong remoteOutstanding;
		private final AtomicInteger drainWip;
		private final AtomicBoolean opening;
		private final AtomicBoolean cancelled;
		private final AtomicBoolean terminated;
		private final AtomicBoolean cancelSent;
		private final AtomicBoolean responseAddressSent;
		private final AtomicReference<String> controlAddress;
		private volatile MessageConsumer<ServiceStreamMessage> responseConsumer;

		private RemoteFlowableSubscription(Subscriber<? super R> downstream,
			String address,
			ServiceMethodRequest request,
			DeliveryOptions deliveryOptions,
			Type itemType) {
			this.downstream = downstream;
			this.address = address;
			this.request = request;
			this.deliveryOptions = deliveryOptions;
			this.itemType = itemType;
			this.responseAddress = ServiceUtils.newStreamAddress("r.");
			this.requested = new AtomicLong();
			this.remoteOutstanding = new AtomicLong();
			this.drainWip = new AtomicInteger();
			this.opening = new AtomicBoolean();
			this.cancelled = new AtomicBoolean();
			this.terminated = new AtomicBoolean();
			this.cancelSent = new AtomicBoolean();
			this.responseAddressSent = new AtomicBoolean();
			this.controlAddress = new AtomicReference<>();
		}

		@Override
		public void request(long n) {
			if (n <= 0L) {
				terminateWithError(new IllegalArgumentException("Flowable request amount must be positive"), true);
				return;
			}
			addRequested(requested, n);
			drainRemoteDemand();
		}

		@Override
		public void cancel() {
			if (cancelled.compareAndSet(false, true)) {
				terminated.set(true);
				settleCancellation();
			}
		}

		private void open() {
			if (!opening.compareAndSet(false, true) || cancelled.get()) {
				return;
			}
			var consumerOptions = new MessageConsumerOptions()
				.setAddress(responseAddress)
				.setLocalOnly(localOnly);
			responseConsumer = vertx.eventBus().consumer(consumerOptions, this::handleStreamMessage);
			responseConsumer.completion()
				.subscribe(this::openRemoteStream, err -> terminateWithError(err, false));
		}

		private void openRemoteStream() {
			if (cancelled.get() || terminated.get()) {
				unregisterResponseConsumer();
				return;
			}
			vertx.eventBus()
				.<ServiceMethodReturnValue<?>>request(address, request, deliveryOptions)
				.onErrorResumeNext(ServiceClient::mapReplyExceptionSingle)
				.subscribe(msg -> {
					var value = msg.body().value();
					if (!(value instanceof String streamControlAddress) || streamControlAddress.isBlank()) {
						terminateWithError(new IllegalStateException("Remote Flowable stream did not return a control address"), false);
						return;
					}
					controlAddress.set(streamControlAddress);
					if (cancelled.get() || terminated.get()) {
						if (cancelled.get()) {
							settleCancellation();
						} else {
							unregisterResponseConsumer();
						}
						return;
					}
					drainRemoteDemand();
					sendRegistrationIfNeeded();
				}, err -> terminateWithError(err, false));
		}

		@SuppressWarnings("unchecked")
		private void handleStreamMessage(Message<ServiceStreamMessage> message) {
			if (cancelled.get() || terminated.get()) {
				return;
			}
			var body = message.body();
			switch (body.kind()) {
				case NEXT -> {
					if (!handleNextValue(body.value())) {
						return;
					}
					drainRemoteDemand();
				}
				case NEXT_BATCH -> {
					if (!(body.value() instanceof List<?> values)) {
						terminateWithError(new IllegalStateException("Remote Flowable batch payload is invalid"), true);
						return;
					}
					for (var value : values) {
						if (!handleNextValue(value)) {
							return;
						}
					}
					drainRemoteDemand();
				}
				case COMPLETE -> {
					if (terminated.compareAndSet(false, true)) {
						unregisterResponseConsumer();
						if (!cancelled.get()) {
							downstream.onComplete();
						}
					}
				}
				case ERROR -> terminateWithError(
					new RemoteServiceException(body.failureCode(), body.errorMessage(), body.errorMessage()),
					false
				);
			}
		}

		@SuppressWarnings("unchecked")
		private boolean handleNextValue(Object encodedValue) {
			if (!tryConsumeRemoteOutstanding() || !tryConsumeRequested()) {
				terminateWithError(new IllegalStateException("Remote Flowable emitted more items than requested"), true);
				return false;
			}
			R value;
			try {
				value = (R) ServiceUtils.castToType(true, itemType, encodedValue);
			} catch (Throwable err) {
				terminateWithError(err, true);
				return false;
			}
			try {
				downstream.onNext(value);
			} catch (Throwable err) {
				terminateWithError(err, true);
				return false;
			}
			return !cancelled.get() && !terminated.get();
		}

		private boolean tryConsumeRemoteOutstanding() {
			while (true) {
				var current = remoteOutstanding.get();
				if (current <= 0L) {
					return false;
				}
				if (remoteOutstanding.compareAndSet(current, current - 1L)) {
					return true;
				}
			}
		}

		private boolean tryConsumeRequested() {
			while (true) {
				var current = requested.get();
				if (current == Long.MAX_VALUE) {
					return true;
				}
				if (current <= 0L) {
					return false;
				}
				if (requested.compareAndSet(current, current - 1L)) {
					return true;
				}
			}
		}

		private void drainRemoteDemand() {
			if (drainWip.getAndIncrement() != 0) {
				return;
			}
			var missed = 1;
			do {
				if (cancelled.get() || terminated.get()) {
					return;
				}
				var streamControlAddress = controlAddress.get();
				if (streamControlAddress == null) {
					missed = drainWip.addAndGet(-missed);
					continue;
				}
				var outstanding = remoteOutstanding.get();
				if (outstanding > FLOWABLE_REMOTE_WINDOW / 2L) {
					missed = drainWip.addAndGet(-missed);
					continue;
				}
				var capacity = FLOWABLE_REMOTE_WINDOW - outstanding;
				if (capacity <= 0L) {
					missed = drainWip.addAndGet(-missed);
					continue;
				}
				var requestedNow = requested.get();
				var demand = requestedNow == Long.MAX_VALUE ? Long.MAX_VALUE : requestedNow - outstanding;
				if (demand <= 0L) {
					missed = drainWip.addAndGet(-missed);
					continue;
				}
				var batch = Math.min(Math.min(demand, capacity), FLOWABLE_REMOTE_REQUEST_BATCH);
				if (remoteOutstanding.compareAndSet(outstanding, outstanding + batch)) {
					sendControlRequest(batch);
				}
				missed = drainWip.addAndGet(-missed);
			} while (missed != 0);
		}

		private void sendControlRequest(long requestCount) {
			var addressForInitialRegistration = responseAddressSent.compareAndSet(false, true) ? responseAddress : null;
			sendControlRequest(addressForInitialRegistration, requestCount);
		}

		private void sendRegistrationIfNeeded() {
			if (responseAddressSent.compareAndSet(false, true)) {
				sendControlRequest(responseAddress, 0L);
			}
		}

		private void sendControlRequest(String responseAddressForControl, long requestCount) {
			var streamControlAddress = controlAddress.get();
			if (streamControlAddress == null || cancelled.get() || terminated.get()) {
				return;
			}
			var control = new ServiceStreamControl(responseAddressForControl, requestCount, false);
			vertx.eventBus()
				.<Boolean>request(streamControlAddress, control, deliveryOptions)
				.subscribe(ignored -> {
				}, err -> terminateWithError(mapReplyException(err), true));
		}

		private void settleCancellation() {
			var streamControlAddress = controlAddress.get();
			if (streamControlAddress == null) {
				// The server does not know the response address until the initial stream request
				// completes, so no stream message can target this consumer yet. If the request
				// completes later, openRemoteStream() will send the cancellation then.
				unregisterResponseConsumer();
				return;
			}
			if (!cancelSent.compareAndSet(false, true)) {
				return;
			}
			var control = new ServiceStreamControl(null, 0L, true);
			vertx.eventBus().<Boolean>request(streamControlAddress, control, deliveryOptions)
				.subscribe(ignored -> unregisterResponseConsumer(),
					ignored -> unregisterResponseConsumer());
		}

		private void terminateWithError(Throwable err, boolean cancelRemote) {
			if (terminated.compareAndSet(false, true)) {
				var signalDownstream = !cancelled.get();
				if (cancelRemote) {
					cancelled.set(true);
					settleCancellation();
				} else {
					unregisterResponseConsumer();
				}
				if (signalDownstream) {
					downstream.onError(err);
				}
			}
		}

		private void unregisterResponseConsumer() {
			var consumer = responseConsumer;
			if (consumer != null && consumer.isRegistered()) {
				consumer.unregister().subscribe(() -> {
				}, ignored -> {
				});
			}
		}
	}

	public T getInstance() {
		return instance;
	}
}
