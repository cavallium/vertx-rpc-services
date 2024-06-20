package it.cavallium.vertx.rpcservice;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import java.util.ArrayList;
import it.cavallium.vertx.rpcservice.DataCodec.DataMessageCodec;

record ServiceMethodRequest(Object[] arguments) {

	static class ServiceMethodRequestMessageCodec implements
		MessageCodec<ServiceMethodRequest, ServiceMethodRequest> {

		public static final ServiceMethodRequestMessageCodec INSTANCE
			= new ServiceMethodRequestMessageCodec();
		private final DataMessageCodec dataCodec;

		private ServiceMethodRequestMessageCodec() {
			this.dataCodec = new DataMessageCodec();
		}

		@Override
		public void encodeToWire(Buffer buffer, ServiceMethodRequest request) {
			for (int i = 0; i < request.arguments.length; i++) {
				var argument = request.arguments[i];
				dataCodec.encodeToWire(buffer, argument);
			}
		}

		@Override
		public ServiceMethodRequest decodeFromWire(int pos, Buffer buffer) {
			var resultArgs = new ArrayList<>();
			while (pos < buffer.length()) {
				resultArgs.add(dataCodec.decodeFromWire(pos, buffer));
				pos = dataCodec.getPos2();
			}
			return new ServiceMethodRequest(resultArgs.toArray(Object[]::new));
		}

		@Override
		public ServiceMethodRequest transform(ServiceMethodRequest request) {
			return request;
		}

		@Override
		public String name() {
			return "ServiceMethodRequestCodec";
		}

		@Override
		public byte systemCodecID() {
			return -1;
		}
	}
}
