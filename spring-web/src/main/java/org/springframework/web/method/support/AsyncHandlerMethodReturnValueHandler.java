/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.web.method.support;

import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;

/**
 * A return value handler that supports async types. Such return value types
 * need to be handled with priority so the async value can be "unwrapped".
 *
 * <p><strong>Note: </strong> implementing this contract is not required but it
 * should be implemented when the handler needs to be prioritized ahead of others.
 * For example custom (async) handlers, by default ordered after built-in
 * handlers, should take precedence over {@code @ResponseBody} or
 * {@code @ModelAttribute} handling, which should occur once the async value is
 * ready. By contrast, built-in (async) handlers are already ordered ahead of
 * sync handlers.
 *
 * @author Rossen Stoyanchev
 * @since 4.2
 *
 *  支持异步类型的返回值处理程序。此类返回值类型需要优先处理，以便异步值可以“展开”。
 *  异步实现此接口并不是必须的，但是若你需要在处理程序之前执行，就需要实现这个接口了~~~
 *  因为默认情况下：我们自定义的Handler它都是在内置的Handler后面去执行的~~~~
 */
public interface AsyncHandlerMethodReturnValueHandler extends HandlerMethodReturnValueHandler {

	/**
	 * Whether the given return value represents asynchronous computation.
	 * @param returnValue the value returned from the handler method
	 * @param returnType the return type
	 * @return {@code true} if the return value type represents an async value
	 *  给定的返回值是否表示异步计算
	 */
	boolean isAsyncReturnValue(@Nullable Object returnValue, MethodParameter returnType);

}
