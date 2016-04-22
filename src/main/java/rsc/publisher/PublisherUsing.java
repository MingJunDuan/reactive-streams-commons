package rsc.publisher;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.*;

import org.reactivestreams.*;

import rsc.flow.*;
import rsc.util.*;

/**
 * Uses a resource, generated by a supplier for each individual Subscriber,
 * while streaming the values from a
 * Publisher derived from the same resource and makes sure the resource is released
 * if the sequence terminates or the Subscriber cancels.
 * <p>
 * <p>
 * Eager resource cleanup happens just before the source termination and exceptions
 * raised by the cleanup Consumer may override the terminal even. Non-eager
 * cleanup will drop any exception.
 *
 * @param <T> the value type streamed
 * @param <S> the resource type
 */
public final class PublisherUsing<T, S> 
extends Px<T>
        implements Receiver, Fuseable {

    final Callable<S> resourceSupplier;

    final Function<? super S, ? extends Publisher<? extends T>> sourceFactory;

    final Consumer<? super S> resourceCleanup;

    final boolean eager;

    public PublisherUsing(Callable<S> resourceSupplier,
                          Function<? super S, ? extends Publisher<? extends T>> sourceFactory, Consumer<? super S>
                                  resourceCleanup,
                          boolean eager) {
        this.resourceSupplier = Objects.requireNonNull(resourceSupplier, "resourceSupplier");
        this.sourceFactory = Objects.requireNonNull(sourceFactory, "sourceFactory");
        this.resourceCleanup = Objects.requireNonNull(resourceCleanup, "resourceCleanup");
        this.eager = eager;
    }

    @Override
    public Object upstream() {
        return resourceSupplier;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        S resource;

        try {
            resource = resourceSupplier.call();
        } catch (Throwable e) {
            ExceptionHelper.throwIfFatal(e);
            EmptySubscription.error(s, ExceptionHelper.unwrap(e));
            return;
        }

        Publisher<? extends T> p;

        try {
            p = sourceFactory.apply(resource);
        } catch (Throwable e) {

            try {
                resourceCleanup.accept(resource);
            } catch (Throwable ex) {
                ExceptionHelper.throwIfFatal(ex);
                ex.addSuppressed(ExceptionHelper.unwrap(e));
                e = ex;
            }

            EmptySubscription.error(s, ExceptionHelper.unwrap(e));
            return;
        }

        if (p == null) {
            Throwable e = new NullPointerException("The sourceFactory returned a null value");
            try {
                resourceCleanup.accept(resource);
            } catch (Throwable ex) {
                ExceptionHelper.throwIfFatal(ex);
                Throwable _ex = ExceptionHelper.unwrap(ex);
                _ex.addSuppressed(e);
                e = _ex;
            }

            EmptySubscription.error(s, e);
            return;
        }

        if (p instanceof Fuseable) {
            p.subscribe(new PublisherUsingFuseableSubscriber<>(s, resourceCleanup, resource, eager));
        } else {
            p.subscribe(new PublisherUsingSubscriber<>(s, resourceCleanup, resource, eager));
        }
    }

    static final class PublisherUsingSubscriber<T, S>
      implements Subscriber<T>, QueueSubscription<T> {

        final Subscriber<? super T> actual;

        final Consumer<? super S> resourceCleanup;

        final S resource;

        final boolean eager;

        Subscription s;

        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublisherUsingSubscriber> WIP =
          AtomicIntegerFieldUpdater.newUpdater(PublisherUsingSubscriber.class, "wip");

        public PublisherUsingSubscriber(Subscriber<? super T> actual, Consumer<? super S> resourceCleanup, S
                resource, boolean eager) {
            this.actual = actual;
            this.resourceCleanup = resourceCleanup;
            this.resource = resource;
            this.eager = eager;
        }

        @Override
        public void request(long n) {
            s.request(n);
        }

        @Override
        public void cancel() {
            if (WIP.compareAndSet(this, 0, 1)) {
                s.cancel();

                cleanup();
            }
        }

        void cleanup() {
            try {
                resourceCleanup.accept(resource);
            } catch (Throwable e) {
                UnsignalledExceptions.onErrorDropped(e);
            }
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;

                actual.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T t) {
            actual.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            if (eager) {
                try {
                    resourceCleanup.accept(resource);
                } catch (Throwable e) {
                    ExceptionHelper.throwIfFatal(e);
                    Throwable _e = ExceptionHelper.unwrap(e);
                    _e.addSuppressed(t);
                    t = _e;
                }
            }

            actual.onError(t);

            if (!eager) {
                cleanup();
            }
        }

        @Override
        public void onComplete() {
            if (eager) {
                try {
                    resourceCleanup.accept(resource);
                } catch (Throwable e) {
                    ExceptionHelper.throwIfFatal(e);
                    actual.onError(ExceptionHelper.unwrap(e));
                    return;
                }
            }

            actual.onComplete();

            if (!eager) {
                cleanup();
            }
        }
        
        @Override
        public int requestFusion(int requestedMode) {
            return NONE; // always reject, upstream turned out to be non-fuseable after all
        }
        
        @Override
        public void clear() {
            // ignoring fusion methods
        }
        
        @Override
        public boolean isEmpty() {
            // ignoring fusion methods
            return wip != 0;
        }
        
        @Override
        public T poll() {
            return null;
        }
        
        @Override
        public int size() {
            return 0;
        }
    }

    static final class PublisherUsingFuseableSubscriber<T, S>
    implements Subscriber<T>, QueueSubscription<T> {

        final Subscriber<? super T> actual;

        final Consumer<? super S> resourceCleanup;

        final S resource;

        final boolean eager;

        QueueSubscription<T> s;

        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublisherUsingFuseableSubscriber> WIP =
        AtomicIntegerFieldUpdater.newUpdater(PublisherUsingFuseableSubscriber.class, "wip");

        int mode;
        
        public PublisherUsingFuseableSubscriber(Subscriber<? super T> actual, Consumer<? super S> resourceCleanup, S
                resource, boolean eager) {
            this.actual = actual;
            this.resourceCleanup = resourceCleanup;
            this.resource = resource;
            this.eager = eager;
        }

        @Override
        public void request(long n) {
            s.request(n);
        }

        @Override
        public void cancel() {
            if (WIP.compareAndSet(this, 0, 1)) {
                s.cancel();

                cleanup();
            }
        }

        void cleanup() {
            try {
                resourceCleanup.accept(resource);
            } catch (Throwable e) {
                UnsignalledExceptions.onErrorDropped(e);
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = (QueueSubscription<T>)s;

                actual.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T t) {
            actual.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            if (eager) {
                try {
                    resourceCleanup.accept(resource);
                } catch (Throwable e) {
                    ExceptionHelper.throwIfFatal(e);
                    Throwable _e = ExceptionHelper.unwrap(e);
                    _e.addSuppressed(t);
                    t = _e;
                }
            }

            actual.onError(t);

            if (!eager) {
                cleanup();
            }
        }

        @Override
        public void onComplete() {
            if (eager) {
                try {
                    resourceCleanup.accept(resource);
                } catch (Throwable e) {
                    ExceptionHelper.throwIfFatal(e);
                    actual.onError(ExceptionHelper.unwrap(e));
                    return;
                }
            }

            actual.onComplete();

            if (!eager) {
                cleanup();
            }
        }
        
        @Override
        public void clear() {
            s.clear();
        }
        
        @Override
        public boolean isEmpty() {
            return s.isEmpty();
        }
        
        @Override
        public T poll() {
            T v = s.poll();
            
            if (v == null && mode == SYNC) {
                resourceCleanup.accept(resource);
            }
            return v;
        }
        
        @Override
        public int requestFusion(int requestedMode) {
            int m = s.requestFusion(requestedMode);
            mode = m;
            return m;
        }
        
        @Override
        public int size() {
            return s.size();
        }
    }

}
