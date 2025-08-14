package it.cavallium.vertx.rpcservice;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.rxjava3.core.Vertx;
import it.cavallium.vertx.rpcservice.ServiceMethodRequest.ServiceMethodRequestMessageCodec;
import it.cavallium.vertx.rpcservice.ServiceMethodReturnValue.ServiceMethodReturnValueMessageCodec;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

public class ServiceClient<T> {

	private final Vertx vertx;
	private final T instance;

	enum ReturnArity {
		COMPLETABLE,
		MAYBE,
		SINGLE
	}

	private record MethodData(String address, Type returnType, ReturnArity arity, int timeout) {}

	@SuppressWarnings("unchecked")
	public ServiceClient(Vertx vertx, Class<T> serviceClass) {
		this.vertx = vertx;
		ServiceUtils.tryRegisterDefaultCodec(vertx, ServiceMethodRequest.class, ServiceMethodRequestMessageCodec.INSTANCE);
		ServiceUtils.tryRegisterDefaultCodec(vertx, ServiceMethodReturnValue.class, ServiceMethodReturnValueMessageCodec.INSTANCE);

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
									+ "\", it should be Single<?> or Maybe<?> with a single type parameter");
						}
						var returnTypeInner = typeArguments[0];
						return new MethodData(address, returnTypeInner, arity, annotation.timeout());
					} else {
						throw new UnsupportedOperationException(
							"Method return type is not valid for service \"" + serviceClass + "\", method \"" + method
								+ "\", it should be Single<?> or Maybe<?> with a valid type parameter");
					}
				}
			}));
	}

	static <T> @NotNull ReturnArity getReturnArity(Class<T> serviceClass, Method method) {
		Class<?> returnTypeClass = method.getReturnType();
		ReturnArity arity;
		if (returnTypeClass.equals(Completable.class)) {
			arity = ReturnArity.COMPLETABLE;
		} else if (returnTypeClass.equals(Maybe.class)) {
			arity = ReturnArity.MAYBE;
		} else if (returnTypeClass.equals(Single.class)) {
			arity = ReturnArity.SINGLE;
		} else {
			throw new UnsupportedOperationException(
				"Method return type is not valid for service \"" + serviceClass + "\", method \"" + method
					+ "\", it should be Single<?>, Maybe<?>, or Completable");
		}
		return arity;
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
					.collect(Collectors.toMap(Map.Entry::getKey, e -> {
						var deliveryOptions = new DeliveryOptions();
						deliveryOptions.setSendTimeout(e.getValue().timeout());
						return deliveryOptions;
					}));
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

			Type returnType;
			if (methodData.arity != ReturnArity.COMPLETABLE) {
				var genericReturnType = (ParameterizedType) method.getGenericReturnType();
				returnType = genericReturnType.getActualTypeArguments()[0];
			} else {
				returnType = null;
			}

			return switch (methodData.arity) {
				case COMPLETABLE -> requestSingle.ignoreElement();
				case MAYBE -> requestSingle.mapOptional(msg -> {
					var value = msg.body().value();
					return Optional.ofNullable(ServiceUtils.castToType(returnType, value));
				});
				case SINGLE -> requestSingle.map(msg -> {
					var value = msg.body().value();
					return Objects.requireNonNull(ServiceUtils.castToType(returnType, value));
				});
			};
		}
	}

	public T getInstance() {
		return instance;
	}
}
