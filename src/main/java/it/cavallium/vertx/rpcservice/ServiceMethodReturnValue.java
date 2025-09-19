package it.cavallium.vertx.rpcservice;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import it.cavallium.vertx.rpcservice.DataCodec.DataMessageCodec;

import java.util.Base64;

record ServiceMethodReturnValue<T>(T value) {


	@SuppressWarnings("rawtypes")
	static class ServiceMethodReturnValueMessageCodec implements
		MessageCodec<ServiceMethodReturnValue, ServiceMethodReturnValue> {

		public static final ServiceMethodReturnValueMessageCodec INSTANCE
			= new ServiceMethodReturnValueMessageCodec();
		private final DataMessageCodec dataCodec;

		private ServiceMethodReturnValueMessageCodec() {
			this.dataCodec = new DataMessageCodec();
		}

		@Override
		public void encodeToWire(Buffer buffer, ServiceMethodReturnValue request) {
			if (request.value != null && request.value.getClass() == byte[].class) {
				dataCodec.encodeToWire(buffer, Base64.getEncoder().encodeToString((byte[]) request.value));
			}
			dataCodec.encodeToWire(buffer, request.value);
		}

		@Override
		public ServiceMethodReturnValue<?> decodeFromWire(int pos, Buffer buffer) {
			return new ServiceMethodReturnValue<>(dataCodec.decodeFromWire(pos, buffer));
		}

		@Override
		public ServiceMethodReturnValue<?> transform(ServiceMethodReturnValue request) {
			return request;
		}

		@Override
		public String name() {
			return "ServiceMethodReturnValueCodec";
		}

		@Override
		public byte systemCodecID() {
			return -1;
		}
	}
}
