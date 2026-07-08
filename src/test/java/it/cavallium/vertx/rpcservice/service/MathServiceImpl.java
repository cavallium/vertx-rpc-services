package it.cavallium.vertx.rpcservice.service;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

class MathServiceImpl implements MathService {

	private final AtomicLong backpressureRequests = new AtomicLong();
	private final AtomicBoolean backpressureSubscribed = new AtomicBoolean();
	private final AtomicLong trackedWindowOutstanding = new AtomicLong();
	private final AtomicLong trackedWindowMaxOutstanding = new AtomicLong();
	private final AtomicLong rangeSubscriptions = new AtomicLong();
	private final AtomicBoolean neverFlowableSubscribed = new AtomicBoolean();
	private final AtomicLong neverFlowableRequests = new AtomicLong();
	private final AtomicBoolean neverFlowableCancelled = new AtomicBoolean();

	@Override
	public Single<Boolean> calculateNot(boolean a) {
		return Single.just(!a);
	}

	@Override
	public Single<Boolean> calculateAnd(boolean a, boolean b) {
		return Single.just(a & b);
	}

	@Override
	public Single<Boolean> calculateOr(boolean a, boolean b) {
		return Single.just(a | b);
	}

	@Override
	public Completable calculateCompletable() {
		return Completable.complete();
	}

	@Override
	public Single<Boolean[]> calculateMergeToArray(boolean a, boolean b) {
		return Single.just(new Boolean[]{a, b});
	}

	@Override
	public Single<List<Boolean>> calculateMergeToList(boolean a, boolean b) {
		return Single.just(List.of(a, b));
	}

	@Override
	public Single<Boolean> calculateListOr(List<Boolean> input) {
		return Single.just(input.stream().reduce(false, (a, b) -> a | b));
	}

	@Override
	public Single<Boolean> calculateArrayOr(Boolean[] input) {
		return Single.just(Arrays.stream(input).reduce(false, (a, b) -> a | b));
	}

	@Override
	public Single<ComputedBooleanOperation> calculateCustomRecordOr(BooleanOperation op) {
		return Single.just(new ComputedBooleanOperation(op, op.a() | op.b()));
	}

	@Override
	public Maybe<Boolean> calculateMaybe(boolean shouldReturn) {
		return shouldReturn ? Maybe.just(true) : Maybe.empty();
	}

	@Override
	public Single<Boolean> failWithNestedThrowable() {
		var rootCause = new IllegalStateException("root cause marker");
		var failure = new IllegalArgumentException("top-level marker", rootCause);
		failure.addSuppressed(new UnsupportedOperationException("suppressed marker"));
		return Single.error(failure);
	}

	@Override
	public Flowable<Integer> calculateRange(int start, int count) {
		return Flowable.range(start, count)
			.doOnSubscribe(ignored -> rangeSubscriptions.incrementAndGet());
	}

	@Override
	public Flowable<Integer> calculateBackpressuredRange(int count) {
		backpressureRequests.set(0L);
		backpressureSubscribed.set(false);
		return Flowable.range(0, count)
			.doOnSubscribe(ignored -> backpressureSubscribed.set(true))
			.doOnRequest(backpressureRequests::addAndGet);
	}

	@Override
	public Flowable<Integer> calculateTrackedWindowRange(int count) {
		trackedWindowOutstanding.set(0L);
		trackedWindowMaxOutstanding.set(0L);
		return Flowable.range(0, count)
			.doOnRequest(this::addTrackedWindowDemand)
			.doOnNext(ignored -> trackedWindowOutstanding.decrementAndGet());
	}

	@Override
	public Flowable<Integer> calculateEmptyFlowable() {
		return Flowable.empty();
	}

	@Override
	public Flowable<Integer> failWithNestedThrowableFlowable() {
		var rootCause = new IllegalStateException("root cause marker");
		var failure = new IllegalArgumentException("top-level marker", rootCause);
		failure.addSuppressed(new UnsupportedOperationException("suppressed marker"));
		return Flowable.error(failure);
	}

	@Override
	public Flowable<Integer> failFlowableAfterItems(int count) {
		var failure = new IllegalArgumentException("stream failure marker");
		return Flowable.range(0, count)
			.concatWith(Flowable.error(failure));
	}

	@Override
	public Flowable<Integer> calculateNeverFlowable() {
		neverFlowableSubscribed.set(false);
		neverFlowableRequests.set(0L);
		neverFlowableCancelled.set(false);
		return Flowable.<Integer>never()
			.doOnSubscribe(ignored -> neverFlowableSubscribed.set(true))
			.doOnRequest(neverFlowableRequests::addAndGet)
			.doOnCancel(() -> neverFlowableCancelled.set(true));
	}

	@Override
	public Flowable<byte[]> calculateBytesFlowable(byte[] input) {
		return Flowable.just(input);
	}

	long backpressureRequests() {
		return backpressureRequests.get();
	}

	boolean backpressureSubscribed() {
		return backpressureSubscribed.get();
	}

	long trackedWindowMaxOutstanding() {
		return trackedWindowMaxOutstanding.get();
	}

	long rangeSubscriptions() {
		return rangeSubscriptions.get();
	}

	boolean neverFlowableSubscribed() {
		return neverFlowableSubscribed.get();
	}

	long neverFlowableRequests() {
		return neverFlowableRequests.get();
	}

	boolean neverFlowableCancelled() {
		return neverFlowableCancelled.get();
	}

	private void addTrackedWindowDemand(long requested) {
		var outstanding = trackedWindowOutstanding.addAndGet(requested);
		trackedWindowMaxOutstanding.accumulateAndGet(outstanding, Math::max);
	}
}
