package it.cavallium.vertx.rpcservice;

import io.vertx.core.eventbus.MessageCodec;
import io.vertx.rxjava3.core.Vertx;
import java.lang.reflect.Method;
import java.util.Objects;

class ServiceUtils {

	static String getMethodEventBusAddress(Class<?> serviceClass, Method method) {
		return getMethodEventBusAddress(getMethodEventBusAddressPrefix(serviceClass), method);
	}

	static String getMethodEventBusAddress(String prefix, Method method) {
		return prefix + method.getName();
	}

	static String getMethodEventBusAddressPrefix(Class<?> serviceClass) {
		return "t_service_" + serviceClass.getSimpleName() + "#";
	}

	@SuppressWarnings("StatementWithEmptyBody")
	public static <T> void tryRegisterDefaultCodec(Vertx vertx,
		Class<T> serviceMethodRequestClass,
		MessageCodec<T, ?> codec) {
		try {
			vertx.eventBus().getDelegate().registerDefaultCodec(serviceMethodRequestClass, codec);
		} catch (IllegalStateException ex) {
			if (!Objects.requireNonNullElse(ex.getMessage(), "").startsWith("Already a default codec registered for class")) {
				throw ex;
			} else {
				// ignored
			}
		}
	}
}
