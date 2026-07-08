package it.cavallium.vertx.rpcservice.service;

import io.vertx.rxjava3.core.Vertx;
import it.cavallium.vertx.rpcservice.ServiceClient;
import it.cavallium.vertx.rpcservice.ServiceServer;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ServiceRpcBenchmark {

	@Param({"1", "128", "4096"})
	public int itemCount;

	private Vertx vertx;
	private ServiceServer<MathService> server;
	private MathService client;
	private MathService direct;

	@Setup(Level.Trial)
	public void setup() {
		vertx = Vertx.vertx();
		direct = new MathServiceImpl();
		server = new ServiceServer<>(vertx, direct, MathService.class);
		client = new ServiceClient<>(vertx, MathService.class).getInstance();
	}

	@TearDown(Level.Trial)
	public void tearDown() {
		try {
			if (server != null) {
				server.rxClose().blockingAwait();
			}
		} finally {
			if (vertx != null) {
				vertx.close().blockingAwait();
			}
		}
	}

	@Benchmark
	@BenchmarkMode(Mode.Throughput)
	@OutputTimeUnit(TimeUnit.SECONDS)
	public boolean singleRpcThroughput() {
		return client.calculateAnd(true, true).blockingGet();
	}

	@Benchmark
	@BenchmarkMode(Mode.AverageTime)
	@OutputTimeUnit(TimeUnit.MICROSECONDS)
	public boolean singleRpcAverageLatency() {
		return client.calculateAnd(true, true).blockingGet();
	}

	@Benchmark
	@BenchmarkMode(Mode.SampleTime)
	@OutputTimeUnit(TimeUnit.MICROSECONDS)
	public boolean singleRpcSampleLatency() {
		return client.calculateAnd(true, true).blockingGet();
	}

	@Benchmark
	@BenchmarkMode(Mode.Throughput)
	@OutputTimeUnit(TimeUnit.SECONDS)
	public long flowableRpcRangeSumThroughput() {
		return client.calculateRange(0, itemCount)
			.reduce(0L, Long::sum)
			.blockingGet();
	}

	@Benchmark
	@BenchmarkMode(Mode.AverageTime)
	@OutputTimeUnit(TimeUnit.MICROSECONDS)
	public long flowableRpcRangeSumAverageLatency() {
		return client.calculateRange(0, itemCount)
			.reduce(0L, Long::sum)
			.blockingGet();
	}

	@Benchmark
	@BenchmarkMode(Mode.SampleTime)
	@OutputTimeUnit(TimeUnit.MICROSECONDS)
	public long flowableRpcRangeSumSampleLatency() {
		return client.calculateRange(0, itemCount)
			.reduce(0L, Long::sum)
			.blockingGet();
	}

	@Benchmark
	@BenchmarkMode(Mode.Throughput)
	@OutputTimeUnit(TimeUnit.SECONDS)
	public long flowableDirectRangeSumThroughput() {
		return direct.calculateRange(0, itemCount)
			.reduce(0L, Long::sum)
			.blockingGet();
	}

	@Benchmark
	@BenchmarkMode(Mode.AverageTime)
	@OutputTimeUnit(TimeUnit.MICROSECONDS)
	public long flowableDirectRangeSumAverageLatency() {
		return direct.calculateRange(0, itemCount)
			.reduce(0L, Long::sum)
			.blockingGet();
	}
}
