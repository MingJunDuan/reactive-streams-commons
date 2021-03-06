package rsc.publisher;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.BooleanSupplier;

import org.reactivestreams.*;

import rsc.documentation.BackpressureMode;
import rsc.documentation.BackpressureSupport;
import rsc.documentation.FusionMode;
import rsc.documentation.FusionSupport;
import rsc.flow.*;
import rsc.publisher.PublisherTakeLastOne.PublisherTakeLastOneSubscriber;
import rsc.flow.Trackable;
import rsc.subscriber.SubscriptionHelper;
import rsc.util.*;

/**
 * Emits the last N values the source emitted before its completion.
 *
 * @param <T> the value type
 */
@BackpressureSupport(input = BackpressureMode.UNBOUNDED, output = BackpressureMode.BOUNDED)
@FusionSupport(input = { FusionMode.NONE }, output = { FusionMode.ASYNC })
public final class PublisherTakeLast<T> extends PublisherSource<T, T> implements Fuseable {

    final int n;

    public PublisherTakeLast(Publisher<? extends T> source, int n) {
        super(source);
        if (n < 0) {
            throw new IllegalArgumentException("n >= required but it was " + n);
        }
        this.n = n;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        if (n == 0) {
            source.subscribe(new PublisherTakeLastZeroSubscriber<>(s));
        } else if (n == 1) {
            source.subscribe(new PublisherTakeLastOneSubscriber<>(s));
        } else {
            source.subscribe(new PublisherTakeLastManySubscriber<>(s, n));
        }
    }


    @Override
    public long getPrefetch() {
        return Long.MAX_VALUE;
    }

    static final class PublisherTakeLastZeroSubscriber<T> implements Subscriber<T>, Producer, Subscription,
                                                                     Receiver {

        final Subscriber<? super T> actual;
        
        Subscription s;

        public PublisherTakeLastZeroSubscriber(Subscriber<? super T> actual) {
            this.actual = actual;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;
                
                actual.onSubscribe(this);

                s.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(T t) {
            // ignoring all values
        }

        @Override
        public void onError(Throwable t) {
            actual.onError(t);
        }

        @Override
        public void onComplete() {
            actual.onComplete();
        }

        @Override
        public Object downstream() {
            return actual;
        }
        
        @Override
        public void request(long n) {
            s.request(n);
        }
        
        @Override
        public void cancel() {
            s.cancel();
        }
        
        @Override
        public Object upstream() {
            return s;
        }
    }

    static final class PublisherTakeLastManySubscriber<T>
      implements Subscriber<T>, Subscription, BooleanSupplier, Producer, Trackable, Receiver {

        final Subscriber<? super T> actual;

        final int n;

        volatile boolean cancelled;

        Subscription s;

        final ArrayDeque<T> buffer;

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<PublisherTakeLastManySubscriber> REQUESTED =
          AtomicLongFieldUpdater.newUpdater(PublisherTakeLastManySubscriber.class, "requested");

        public PublisherTakeLastManySubscriber(Subscriber<? super T> actual, int n) {
            this.actual = actual;
            this.n = n;
            this.buffer = new ArrayDeque<>();
        }

        @Override
        public boolean getAsBoolean() {
            return cancelled;
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                DrainHelper.postCompleteRequest(n, actual, buffer, REQUESTED, this, this);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            s.cancel();
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;

                actual.onSubscribe(this);

                s.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(T t) {
            ArrayDeque<T> bs = buffer;

            if (bs.size() == n) {
                bs.poll();
            }
            bs.offer(t);
        }

        @Override
        public void onError(Throwable t) {
            actual.onError(t);
        }

        @Override
        public void onComplete() {

            DrainHelper.postComplete(actual, buffer, REQUESTED, this, this);
        }

        @Override
        public Object upstream() {
            return s;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public long getPending() {
            return buffer.size();
        }

        @Override
        public long getCapacity() {
            return n;
        }

        @Override
        public Object downstream() {
            return actual;
        }
    }
}
