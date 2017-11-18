/*
 * Copyright (C) 2017. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.autodispose;

import com.uber.autodispose.observers.AutoDisposingSubscriber;
import io.reactivex.Maybe;
import io.reactivex.disposables.Disposable;
import io.reactivex.internal.subscriptions.EmptySubscription;
import io.reactivex.observers.DisposableMaybeObserver;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

final class AutoDisposingSubscriberImpl<T> implements AutoDisposingSubscriber<T> {

  private final AtomicReference<Subscription> mainSubscription = new AtomicReference<>();
  private final AtomicReference<Disposable> lifecycleDisposable = new AtomicReference<>();
  private final Maybe<?> lifecycle;
  private final Subscriber<? super T> delegate;

  AutoDisposingSubscriberImpl(Maybe<?> lifecycle, Subscriber<? super T> delegate) {
    this.lifecycle = lifecycle;
    this.delegate = delegate;
  }

  @Override public Subscriber<? super T> delegateSubscriber() {
    return delegate;
  }

  @Override public void onSubscribe(final Subscription s) {
    if (AutoDisposeEndConsumerHelper.setOnce(lifecycleDisposable,
        lifecycle.subscribeWith(new DisposableMaybeObserver<Object>() {
          @Override public void onSuccess(Object o) {
            callMainSubscribeIfNecessary(s);
            AutoDisposingSubscriberImpl.this.dispose();
          }

          @Override public void onError(Throwable e) {
            callMainSubscribeIfNecessary(s);
            AutoDisposingSubscriberImpl.this.onError(e);
          }

          @Override public void onComplete() {
            callMainSubscribeIfNecessary(s);
            // Noop - we're unbound now
          }
        }),
        getClass())) {
      if (AutoDisposeEndConsumerHelper.setOnce(mainSubscription, s, getClass())) {
        delegate.onSubscribe(this);
      }
    }
  }

  /**
   * Requests the specified amount from the upstream if its Subscription is set via
   * onSubscribe already.
   * <p>Note that calling this method before a Subscription is set via onSubscribe
   * leads to NullPointerException and meant to be called from inside onStart or
   * onNext.
   *
   * @param n the request amount, positive
   */
  @Override public void request(long n) {
    Subscription s = mainSubscription.get();
    if (s != null) {
      s.request(n);
    }
  }

  /**
   * Cancels the Subscription set via onSubscribe or makes sure a
   * Subscription set asynchronously (later) is cancelled immediately.
   * <p>This method is thread-safe and can be exposed as a public API.
   */
  @Override public void cancel() {
    AutoDisposableHelper.dispose(lifecycleDisposable);
    AutoSubscriptionHelper.cancel(mainSubscription);
  }

  private void lazyCancel() {
    AutoDisposableHelper.dispose(lifecycleDisposable);
    mainSubscription.lazySet(AutoSubscriptionHelper.CANCELLED);
  }

  @SuppressWarnings("WeakerAccess") // Avoiding synthetic accessors
  void callMainSubscribeIfNecessary(Subscription s) {
    // If we've never actually started the upstream subscription (i.e. requested immediately in
    // onSubscribe and had a terminal event), we need to still send an empty subscription instance
    // to abide by the Subscriber contract.
    if (AutoSubscriptionHelper.setIfNotSet(mainSubscription, s)) {
      delegate.onSubscribe(EmptySubscription.INSTANCE);
    }
  }

  @Override public boolean isDisposed() {
    return mainSubscription.get() == AutoSubscriptionHelper.CANCELLED;
  }

  @Override public void dispose() {
    cancel();
  }

  @Override public void onNext(T value) {
    if (!isDisposed()) {
      delegate.onNext(value);
    }
  }

  @Override public void onError(Throwable e) {
    if (!isDisposed()) {
      lazyCancel();
      delegate.onError(e);
    }
  }

  @Override public void onComplete() {
    if (!isDisposed()) {
      lazyCancel();
      delegate.onComplete();
    }
  }
}
