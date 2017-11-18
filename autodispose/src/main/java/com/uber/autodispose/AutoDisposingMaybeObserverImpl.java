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

import com.uber.autodispose.observers.AutoDisposingMaybeObserver;
import io.reactivex.Maybe;
import io.reactivex.MaybeObserver;
import io.reactivex.disposables.Disposable;
import io.reactivex.disposables.Disposables;
import io.reactivex.observers.DisposableMaybeObserver;
import java.util.concurrent.atomic.AtomicReference;

final class AutoDisposingMaybeObserverImpl<T> implements AutoDisposingMaybeObserver<T> {

  private final AtomicReference<Disposable> mainDisposable = new AtomicReference<>();
  private final AtomicReference<Disposable> lifecycleDisposable = new AtomicReference<>();
  private final Maybe<?> lifecycle;
  private final MaybeObserver<? super T> delegate;

  AutoDisposingMaybeObserverImpl(Maybe<?> lifecycle, MaybeObserver<? super T> delegate) {
    this.lifecycle = lifecycle;
    this.delegate = delegate;
  }

  @Override public MaybeObserver<? super T> delegateObserver() {
    return delegate;
  }

  @Override public void onSubscribe(final Disposable d) {
    if (AutoDisposeEndConsumerHelper.setOnce(lifecycleDisposable,
        lifecycle.subscribeWith(new DisposableMaybeObserver<Object>() {
          @Override public void onSuccess(Object o) {
            callMainSubscribeIfNecessary(d);
            AutoDisposingMaybeObserverImpl.this.dispose();
          }

          @Override public void onError(Throwable e) {
            callMainSubscribeIfNecessary(d);
            AutoDisposingMaybeObserverImpl.this.onError(e);
          }

          @Override public void onComplete() {
            callMainSubscribeIfNecessary(d);
            // Noop - we're unbound now
          }
        }),
        getClass())) {
      if (AutoDisposeEndConsumerHelper.setOnce(mainDisposable, d, getClass())) {
        delegate.onSubscribe(this);
      }
    }
  }

  @Override public boolean isDisposed() {
    return mainDisposable.get() == AutoDisposableHelper.DISPOSED;
  }

  @Override public void dispose() {
    AutoDisposableHelper.dispose(lifecycleDisposable);
    AutoDisposableHelper.dispose(mainDisposable);
  }

  private void lazyDispose() {
    AutoDisposableHelper.dispose(lifecycleDisposable);
    mainDisposable.lazySet(AutoDisposableHelper.DISPOSED);
  }

  @SuppressWarnings("WeakerAccess") // Avoiding synthetic accessors
  void callMainSubscribeIfNecessary(Disposable d) {
    // If we've never actually called the downstream onSubscribe (i.e. requested immediately in
    // onSubscribe and had a terminal event), we need to still send an empty disposable instance
    // to abide by the Observer contract.
    if (AutoDisposableHelper.setIfNotSet(mainDisposable, d)) {
      delegate.onSubscribe(Disposables.disposed());
    }
  }

  @Override public void onSuccess(T value) {
    if (!isDisposed()) {
      lazyDispose();
      delegate.onSuccess(value);
    }
  }

  @Override public void onError(Throwable e) {
    if (!isDisposed()) {
      lazyDispose();
      delegate.onError(e);
    }
  }

  @Override public void onComplete() {
    if (!isDisposed()) {
      lazyDispose();
      delegate.onComplete();
    }
  }
}
