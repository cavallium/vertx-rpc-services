package it.cavallium.vertx.rpcservice;

import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import it.cavallium.vertx.rpcservice.ServiceClient.ReturnArity;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

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

	public static <T> T castToType(Type returnType, @Nullable Object value) {
		if (returnType == null) return null;
		Class<?> returnTypeClass;
		ParameterizedType returnTypeParametrized;
		if (returnType instanceof ParameterizedType parameterizedType) {
			returnTypeParametrized = parameterizedType;
		} else {
			returnTypeParametrized = null;
		}
		if (returnType instanceof Class<?> c) {
			returnTypeClass = c;
		} else {
			returnTypeClass = null;
		}

		Object result;
		if (value != null) {
			if (value.getClass() == String.class && returnTypeClass != null && returnTypeClass.isEnum()) {
				//noinspection rawtypes,unchecked
				result = Enum.valueOf((Class) returnTypeClass, (String) value);
			} else if (value.getClass() == String.class && returnTypeClass == UUID.class) {
				result = UUID.fromString((String) value);
			} else if (value.getClass() == Integer.class && returnTypeClass == Long.class) {
				result = (long) (int) (Integer) value;
			} else if (value.getClass() == Double.class && returnTypeClass == Instant.class) {
				result = Instant.ofEpochSecond((long) (double) (Double) value, (long) (((Double) value) * 1000000000L % 1000000000L));
			} else if (value.getClass() == JsonObject.class && returnTypeClass != null && returnTypeClass != JsonObject.class) {
				result = ((JsonObject) value).mapTo(returnTypeClass);
			} else if (value.getClass() == JsonArray.class && returnTypeClass != null && returnTypeClass != List.class) {
				var valueJsonArray = ((JsonArray) value);
				var size = valueJsonArray.size();
				var resultList = new ArrayList<Object>(size);
				var elementType = returnTypeParametrized.getActualTypeArguments()[0];
				for (Object element : valueJsonArray) {
					if (element instanceof JsonObject jsonObject) {
						resultList.add(jsonObject.mapTo((Class<?>) elementType));
					} else {
						resultList.add(element);
					}
				}
				result = resultList;
			} else {
				result = value;
			}
		} else {
			result = null;
		}
		//noinspection unchecked
		return (T) result;
	}
}
