package it.cavallium.vertx.rpcservice;

import static it.cavallium.vertx.rpcservice.ServiceClient.getReturnArity;
import static it.cavallium.vertx.rpcservice.ServiceUtils.getMethodEventBusAddress;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.functions.Consumer;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.eventbus.Message;
import io.vertx.rxjava3.core.eventbus.MessageConsumer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import it.cavallium.vertx.rpcservice.ServiceMethodReturnValue.ServiceMethodReturnValueMessageCodec;
import it.cavallium.vertx.rpcservice.ServiceMethodRequest.ServiceMethodRequestMessageCodec;

public class ServiceServer<T> implements RxCloseable {

	private final Class<? super T> serviceClass;
	private final List<MessageConsumer<ServiceMethodRequest>> consumers;
	private static final ServiceMethodReturnValue<?> EMPTY_RESULT = new ServiceMethodReturnValue<>(null);

	public ServiceServer(Vertx vertx, T service, Class<? super T> serviceClass) {
		this.serviceClass = serviceClass;
		ServiceUtils.tryRegisterDefaultCodec(vertx, ServiceMethodRequest.class, ServiceMethodRequestMessageCodec.INSTANCE);
		ServiceUtils.tryRegisterDefaultCodec(vertx, ServiceMethodReturnValue.class, ServiceMethodReturnValueMessageCodec.INSTANCE);

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
			.map(definition -> vertx.eventBus().consumer(definition.address, definition.handler))
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
		return msg -> {
			try {
				var req = msg.body();

				if (req.arguments() == null && paramsCount > 0) {
					msg.fail(500, "Arguments array is null, expected " + paramsCount + " arguments");
				}

				if (req.arguments() != null) {
					for (int i = 0; i < req.arguments().length; i++) {
						var arg = req.arguments()[i];
						var parameterType = declaredMethod.getParameterTypes()[i];
						if (arg != null && arg.getClass() == String.class && parameterType == UUID.class) {
							// Replace argument with the decoded version
							req.arguments()[i] = UUID.fromString((String) arg);
						} else if (arg != null && arg.getClass() == Integer.class && parameterType == Long.class) {
							// Replace argument with the decoded version
							req.arguments()[i] = (long) (int) (Integer) arg;
						} else if (arg != null && arg.getClass() == Double.class && parameterType == Instant.class) {
							// Replace argument with the decoded version
							req.arguments()[i] = Instant.ofEpochSecond((long) (double) (Double) arg, (long) (((Double) arg) * 1000000000L % 1000000000L));
						} else if (arg != null && arg.getClass() == JsonObject.class && parameterType != JsonObject.class) {
							// Replace argument with the decoded version
							req.arguments()[i] = ((JsonObject) arg).mapTo(parameterType);
						}
					}
				}

				switch (arity) {
					case COMPLETABLE -> ((Completable) mh.invokeWithArguments(req.arguments()))
						.subscribe(getEmptyReplyHandler(msg), getErrorHandler(msg));
					case MAYBE -> ((Maybe<?>) mh.invokeWithArguments(req.arguments()))
						.subscribe(getReplyHandler(msg), getErrorHandler(msg), getEmptyReplyHandler(msg));
					case SINGLE -> ((Single<?>) mh.invokeWithArguments(req.arguments()))
						.subscribe(getReplyHandler(msg), getErrorHandler(msg));
				}
			} catch (Throwable e) {
				msg.fail(500, e.toString());
			}
		};
	}

	private static @NotNull Consumer<Object> getReplyHandler(Message<ServiceMethodRequest> msg) {
		return ok -> msg.reply(new ServiceMethodReturnValue<>(ok));
	}

	private static @NotNull Consumer<Throwable> getErrorHandler(Message<ServiceMethodRequest> msg) {
		return err -> msg.fail(500, err.toString());
	}

	private static @NotNull Action getEmptyReplyHandler(Message<ServiceMethodRequest> msg) {
		return () -> msg.reply(EMPTY_RESULT);
	}

	@Override
	public Completable rxClose() {
		return Flowable.fromIterable(consumers)
			.flatMapCompletable(MessageConsumer::unregister);
	}
}
