/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.context.request.async;

import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * {@code DeferredResult} provides an alternative to using a {@link Callable} for
 * asynchronous request processing. While a {@code Callable} is executed concurrently
 * on behalf of the application, with a {@code DeferredResult} the application can
 * produce the result from a thread of its choice.
 *
 * <p>Subclasses can extend this class to easily associate additional data or behavior
 * with the {@link DeferredResult}. For example, one might want to associate the user
 * used to create the {@link DeferredResult} by extending the class and adding an
 * additional property for the user. In this way, the user could easily be accessed
 * later without the need to use a data structure to do the mapping.
 *
 * <p>An example of associating additional behavior to this class might be realized
 * by extending the class to implement an additional interface. For example, one
 * might want to implement {@link Comparable} so that when the {@link DeferredResult}
 * is added to a {@link PriorityQueue} it is handled in the correct order.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Rob Winch
 * @since 3.2
 * @param <T> the result type
 *
 * 先明细两个概念：
 *
 * 请求处理线程：处理线程 属于 web 服务器线程，负责 处理用户请求，采用 线程池 管理。
 * 异步线程：异步线程 属于 用户自定义的线程，可采用 线程池管理。
 *
 * 异步模式处理步骤概述如下
 * 1.当Controller返回值是Callable的时候
 * 2.Spring就会将Callable交给TaskExecutor去处理（一个隔离的线程池）
 * 3.与此同时将DispatcherServlet里的拦截器、Filter等等都马上退出主线程，但是response仍然保持打开的状态
 * 4.Callable线程处理完成后，Spring MVC讲请求重新派发给容器**（注意这里的重新派发，和后面讲的拦截器密切相关）**
 * 5.根据Callabel返回结果，继续处理（比如参数绑定、视图解析等等就和之前一样了）~~~~
 *
 * DeferredResult使用方式与Callable类似，但在返回结果上不一样，它返回的时候实际结果可能没有生成，实际的结果可能会在另外的线程里面设置到DeferredResult中去
 * 这个特性非常非常的重要，对后面实现复杂的功能（比如服务端推技术、订单过期时间处理、长轮询、模拟MQ的功能等等高级应用）
 *
 * @Controller
 * @RequestMapping("/async/controller")
 * public class AsyncHelloController {
 *
 *     private List<DeferredResult<String>> deferredResultList = new ArrayList<>();
 *
 *     @ResponseBody
 *     @GetMapping("/hello")
 *     public DeferredResult<String> helloGet() throws Exception {
 *         DeferredResult<String> deferredResult = new DeferredResult<>();
 *
 *         //先存起来，等待触发
 *         deferredResultList.add(deferredResult);
 *         return deferredResult;
 *     }
 *
 *     @ResponseBody
 *     @GetMapping("/setHelloToAll")
 *     public void helloSet() throws Exception {
 *         // 让所有hold住的请求给与响应
 *         deferredResultList.forEach(d -> d.setResult("say hello to all"));
 *     }
 * }
 * 我们第一个请求/hello，会先deferredResult存起来，然后前端页面是一直等待（转圈状态）的。知道我发第二个请求：setHelloToAll，所有的相关页面才会有响应~~
 *
 * 执行过程
 * 1.controller 返回一个DeferredResult，我们把它保存到内存里或者List里面（供后续访问）
 * 2.Spring MVC调用request.startAsync()，开启异步处理
 * 3.与此同时将DispatcherServlet里的拦截器、Filter等等都马上退出主线程，但是response仍然保持打开的状态
 * 4.应用通过另外一个线程（可能是MQ消息、定时任务等）给DeferredResult set值。然后Spring MVC会把这个请求再次派发给servlet容器
 * 5.DispatcherServlet再次被调用，然后处理后续的标准流程
 *
 * DeferredResult的超时处理，采用委托机制，也就是在实例DeferredResult时给予一个超时时长（毫秒），同时在onTimeout中委托（传入）一个新的处理线程（我们可以认为是超时线程）；当超时时间到来，DeferredResult启动超时线程，超时线程处理业务，封装返回数据，给DeferredResult赋值（正确返回的或错误返回的）
 */
public class DeferredResult<T> {

	private static final Object RESULT_NONE = new Object();

	private static final Log logger = LogFactory.getLog(DeferredResult.class);

	//超时时间（ms） 可以不配置
	@Nullable
	private final Long timeoutValue;

	// 相当于超时的话的，传给回调函数的值
	private final Supplier<?> timeoutResult;

	// 这三种回调也都是支持的
	private Runnable timeoutCallback;

	private Consumer<Throwable> errorCallback;

	private Runnable completionCallback;

	// 这个比较强大，就是能把我们结果再交给这个自定义的函数处理了 他是个@FunctionalInterface
	private DeferredResultHandler resultHandler;

	private volatile Object result = RESULT_NONE;

	private volatile boolean expired;


	/**
	 * Create a DeferredResult.
	 */
	public DeferredResult() {
		this(null, () -> RESULT_NONE);
	}

	/**
	 * Create a DeferredResult with a custom timeout value.
	 * <p>By default not set in which case the default configured in the MVC
	 * Java Config or the MVC namespace is used, or if that's not set, then the
	 * timeout depends on the default of the underlying server.
	 * @param timeoutValue timeout value in milliseconds
	 */
	public DeferredResult(Long timeoutValue) {
		this(timeoutValue, () -> RESULT_NONE);
	}

	/**
	 * Create a DeferredResult with a timeout value and a default result to use
	 * in case of timeout.
	 * @param timeoutValue timeout value in milliseconds (ignored if {@code null})
	 * @param timeoutResult the result to use
	 */
	public DeferredResult(@Nullable Long timeoutValue, Object timeoutResult) {
		this.timeoutValue = timeoutValue;
		this.timeoutResult = () -> timeoutResult;
	}

	/**
	 * Variant of {@link #DeferredResult(Long, Object)} that accepts a dynamic
	 * fallback value based on a {@link Supplier}.
	 * @param timeoutValue timeout value in milliseconds (ignored if {@code null})
	 * @param timeoutResult the result supplier to use
	 * @since 5.1.1
	 */
	public DeferredResult(@Nullable Long timeoutValue, Supplier<?> timeoutResult) {
		this.timeoutValue = timeoutValue;
		this.timeoutResult = timeoutResult;
	}


	/**
	 * Return {@code true} if this DeferredResult is no longer usable either
	 * because it was previously set or because the underlying request expired.
	 * <p>The result may have been set with a call to {@link #setResult(Object)},
	 * or {@link #setErrorResult(Object)}, or as a result of a timeout, if a
	 * timeout result was provided to the constructor. The request may also
	 * expire due to a timeout or network error.
	 *
	 *  判断这个DeferredResult是否已经被set过了（被set过的对象，就可以移除了嘛）
	 * 	如果expired表示已经过期了你还没set，也是返回false的
	 * 	Spring4.0之后提供的
	 */
	public final boolean isSetOrExpired() {
		return (this.result != RESULT_NONE || this.expired);
	}

	/**
	 * Return {@code true} if the DeferredResult has been set.
	 * @since 4.0
	 * 没有isSetOrExpired 强大，建议使用上面那个
	 */
	public boolean hasResult() {
		return (this.result != RESULT_NONE);
	}

	/**
	 * Return the result, or {@code null} if the result wasn't set. Since the result
	 * can also be {@code null}, it is recommended to use {@link #hasResult()} first
	 * to check if there is a result prior to calling this method.
	 * @since 4.0
	 *  还可以获得set进去的结果
	 */
	@Nullable
	public Object getResult() {
		Object resultToCheck = this.result;
		return (resultToCheck != RESULT_NONE ? resultToCheck : null);
	}

	/**
	 * Return the configured timeout value in milliseconds.
	 */
	@Nullable
	final Long getTimeoutValue() {
		return this.timeoutValue;
	}

	/**
	 * Register code to invoke when the async request times out.
	 * <p>This method is called from a container thread when an async request
	 * times out before the {@code DeferredResult} has been populated.
	 * It may invoke {@link DeferredResult#setResult setResult} or
	 * {@link DeferredResult#setErrorResult setErrorResult} to resume processing.
	 */
	public void onTimeout(Runnable callback) {
		this.timeoutCallback = callback;
	}

	/**
	 * Register code to invoke when an error occurred during the async request.
	 * <p>This method is called from a container thread when an error occurs
	 * while processing an async request before the {@code DeferredResult} has
	 * been populated. It may invoke {@link DeferredResult#setResult setResult}
	 * or {@link DeferredResult#setErrorResult setErrorResult} to resume
	 * processing.
	 * @since 5.0
	 */
	public void onError(Consumer<Throwable> callback) {
		this.errorCallback = callback;
	}

	/**
	 * Register code to invoke when the async request completes.
	 * <p>This method is called from a container thread when an async request
	 * completed for any reason including timeout and network error. This is useful
	 * for detecting that a {@code DeferredResult} instance is no longer usable.
	 */
	public void onCompletion(Runnable callback) {
		this.completionCallback = callback;
	}

	/**
	 * Provide a handler to use to handle the result value.
	 * @param resultHandler the handler
	 * @see DeferredResultProcessingInterceptor
	 *
	 * 如果你的result还需要处理，可以这是一个resultHandler，会对你设置进去的结果进行处理
	 */
	public final void setResultHandler(DeferredResultHandler resultHandler) {
		Assert.notNull(resultHandler, "DeferredResultHandler is required");
		// Immediate expiration check outside of the result lock
		if (this.expired) {
			return;
		}
		Object resultToHandle;
		synchronized (this) {
			// Got the lock in the meantime: double-check expiration status
			if (this.expired) {
				return;
			}
			resultToHandle = this.result;
			if (resultToHandle == RESULT_NONE) {
				// No result yet: store handler for processing once it comes in
				this.resultHandler = resultHandler;
				return;
			}
		}
		// If we get here, we need to process an existing result object immediately.
		// The decision is made within the result lock; just the handle call outside
		// of it, avoiding any deadlock potential with Servlet container locks.
		try {
			resultHandler.handleResult(resultToHandle);
		}
		catch (Throwable ex) {
			logger.debug("Failed to process async result", ex);
		}
	}

	/**
	 * Set the value for the DeferredResult and handle it.
	 * @param result the value to set
	 * @return {@code true} if the result was set and passed on for handling;
	 * {@code false} if the result was already set or the async request expired
	 * @see #isSetOrExpired()
	 *
	 * 我们发现，这里调用是private方法setResultInternal，我们设置进来的结果result，会经过它的处理
	 * 而它的处理逻辑也很简单，如果我们提供了resultHandler，它会把这个值进一步的交给我们的resultHandler处理
	 * 若我们没有提供此resultHandler，那就保存下这个result即可
	 */
	public boolean setResult(T result) {
		return setResultInternal(result);
	}

	private boolean setResultInternal(Object result) {
		// Immediate expiration check outside of the result lock
		if (isSetOrExpired()) {
			return false;
		}
		DeferredResultHandler resultHandlerToUse;
		synchronized (this) {
			// Got the lock in the meantime: double-check expiration status
			if (isSetOrExpired()) {
				return false;
			}
			// At this point, we got a new result to process
			this.result = result;
			resultHandlerToUse = this.resultHandler;
			if (resultHandlerToUse == null) {
				// No result handler set yet -> let the setResultHandler implementation
				// pick up the result object and invoke the result handler for it.
				return true;
			}
			// Result handler available -> let's clear the stored reference since
			// we don't need it anymore.
			this.resultHandler = null;
		}
		// If we get here, we need to process an existing result object immediately.
		// The decision is made within the result lock; just the handle call outside
		// of it, avoiding any deadlock potential with Servlet container locks.
		resultHandlerToUse.handleResult(result);
		return true;
	}

	/**
	 * Set an error value for the {@link DeferredResult} and handle it.
	 * The value may be an {@link Exception} or {@link Throwable} in which case
	 * it will be processed as if a handler raised the exception.
	 * @param result the error result value
	 * @return {@code true} if the result was set to the error value and passed on
	 * for handling; {@code false} if the result was already set or the async
	 * request expired
	 * @see #isSetOrExpired()
	 *
	 * 发生错误了，也可以设置一个值。这个result会被记下来，当作result
	 * 注意这个和setResult的唯一区别，这里入参是Object类型，而setResult只能set规定的指定类型
	 * 定义成Obj是有原因的：因为我们一般会把Exception等异常对象放进来。。。
	 */
	public boolean setErrorResult(Object result) {
		return setResultInternal(result);
	}


	/**
	 * 拦截器 注意最终finally里面，都可能会调用我们的自己的处理器resultHandler(若存在的话)
	 * afterCompletion不会调用resultHandler~~~~~~~~~~~~~
	 * @return
	 */
	final DeferredResultProcessingInterceptor getInterceptor() {
		return new DeferredResultProcessingInterceptor() {
			@Override
			public <S> boolean handleTimeout(NativeWebRequest request, DeferredResult<S> deferredResult) {
				boolean continueProcessing = true;
				try {
					if (timeoutCallback != null) {
						timeoutCallback.run();
					}
				}
				finally {
					Object value = timeoutResult.get();
					if (value != RESULT_NONE) {
						continueProcessing = false;
						try {
							setResultInternal(value);
						}
						catch (Throwable ex) {
							logger.debug("Failed to handle timeout result", ex);
						}
					}
				}
				return continueProcessing;
			}
			@Override
			public <S> boolean handleError(NativeWebRequest request, DeferredResult<S> deferredResult, Throwable t) {
				try {
					if (errorCallback != null) {
						errorCallback.accept(t);
					}
				}
				finally {
					try {
						setResultInternal(t);
					}
					catch (Throwable ex) {
						logger.debug("Failed to handle error result", ex);
					}
				}
				return false;
			}
			@Override
			public <S> void afterCompletion(NativeWebRequest request, DeferredResult<S> deferredResult) {
				expired = true;
				if (completionCallback != null) {
					completionCallback.run();
				}
			}
		};
	}


	/**
	 * Handles a DeferredResult value when set.
	 * 内部函数式接口 DeferredResultHandler
	 */
	@FunctionalInterface
	public interface DeferredResultHandler {

		void handleResult(Object result);
	}

}
