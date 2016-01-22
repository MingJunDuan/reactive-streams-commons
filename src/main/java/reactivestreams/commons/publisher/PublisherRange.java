package reactivestreams.commons.publisher;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactivestreams.commons.trait.Cancellable;
import reactivestreams.commons.trait.Completable;
import reactivestreams.commons.trait.Requestable;
import reactivestreams.commons.trait.Subscribable;
import reactivestreams.commons.util.BackpressureHelper;
import reactivestreams.commons.util.SubscriptionHelper;

/**
 * Emits a range of integer values.
 */
public final class PublisherRange 
extends PublisherBase<Integer> {

    final long start;

    final long end;

    public PublisherRange(int start, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count >= required but it was " + count);
        }
        long e = (long) start + count;
        if (e - 1 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("start + count must be less than Integer.MAX_VALUE + 1");
        }

        this.start = start;
        this.end = e;
    }

    @Override
    public void subscribe(Subscriber<? super Integer> s) {
        s.onSubscribe(new RangeSubscription<>(s, start, end));
    }

    static final class RangeSubscription<T>
      implements Subscription, Cancellable, Requestable, Completable, Subscribable {

        final Subscriber<? super Integer> actual;

        final long end;

        volatile boolean cancelled;

        long index;

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<RangeSubscription> REQUESTED =
          AtomicLongFieldUpdater.newUpdater(RangeSubscription.class, "requested");

        public RangeSubscription(Subscriber<? super Integer> actual, long start, long end) {
            this.actual = actual;
            this.index = start;
            this.end = end;
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                if (BackpressureHelper.addAndGet(REQUESTED, this, n) == 0) {
                    if (n == Long.MAX_VALUE) {
                        fastPath();
                    } else {
                        slowPath(n);
                    }
                }
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        void fastPath() {
            final long e = end;
            final Subscriber<? super Integer> a = actual;

            for (long i = index; i != e; i++) {
                if (cancelled) {
                    return;
                }

                a.onNext((int) i);
            }

            if (cancelled) {
                return;
            }

            a.onComplete();
        }

        void slowPath(long n) {
            final Subscriber<? super Integer> a = actual;

            long f = end;
            long e = 0;
            long i = index;

            for (; ; ) {

                if (cancelled) {
                    return;
                }

                while (e != n && i != f) {

                    a.onNext((int) i);

                    if (cancelled) {
                        return;
                    }

                    e++;
                    i++;
                }

                if (cancelled) {
                    return;
                }

                if (i == f) {
                    a.onComplete();
                    return;
                }

                n = requested;
                if (n == e) {
                    index = i;
                    n = REQUESTED.addAndGet(this, -e);
                    if (n == 0) {
                        return;
                    }
                    e = 0;
                }
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isStarted() {
            return end != index;
        }

        @Override
        public boolean isTerminated() {
            return end == index;
        }

        @Override
        public Object downstream() {
            return actual;
        }

        @Override
        public Object upstream() {
            return index;
        }

        @Override
        public long requestedFromDownstream() {
            return requested;
        }
    }
}
