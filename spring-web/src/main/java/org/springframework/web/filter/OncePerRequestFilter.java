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

package org.springframework.web.filter;

import java.io.IOException;

import javax.servlet.DispatcherType;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.util.WebUtils;

/**
 * Filter base class that aims to guarantee a single execution per request
 * dispatch, on any servlet container. It provides a {@link #doFilterInternal}
 * method with HttpServletRequest and HttpServletResponse arguments.
 *
 * <p>As of Servlet 3.0, a filter may be invoked as part of a
 * {@link javax.servlet.DispatcherType#REQUEST REQUEST} or
 * {@link javax.servlet.DispatcherType#ASYNC ASYNC} dispatches that occur in
 * separate threads. A filter can be configured in {@code web.xml} whether it
 * should be involved in async dispatches. However, in some cases servlet
 * containers assume different default configuration. Therefore sub-classes can
 * override the method {@link #shouldNotFilterAsyncDispatch()} to declare
 * statically if they should indeed be invoked, <em>once</em>, during both types
 * of dispatches in order to provide thread initialization, logging, security,
 * and so on. This mechanism complements and does not replace the need to
 * configure a filter in {@code web.xml} with dispatcher types.
 *
 * <p>Subclasses may use {@link #isAsyncDispatch(HttpServletRequest)} to
 * determine when a filter is invoked as part of an async dispatch, and use
 * {@link #isAsyncStarted(HttpServletRequest)} to determine when the request
 * has been placed in async mode and therefore the current dispatch won't be
 * the last one for the given request.
 *
 * <p>Yet another dispatch type that also occurs in its own thread is
 * {@link javax.servlet.DispatcherType#ERROR ERROR}. Subclasses can override
 * {@link #shouldNotFilterErrorDispatch()} if they wish to declare statically
 * if they should be invoked <em>once</em> during error dispatches.
 *
 * <p>The {@link #getAlreadyFilteredAttributeName} method determines how to
 * identify that a request is already filtered. The default implementation is
 * based on the configured name of the concrete filter instance.
 *
 * @author Juergen Hoeller
 * @author Rossen Stoyanchev
 * @since 06.12.2003
 *
 * 能够确保在一次请求中只通过一次filter
 * 此方法是为了兼容不同的web container，也就是说并不是所有的container都如我们期望的只过滤一次，servlet版本不同，执行过程也不同
 * 适配了不同的web容器，以及对异步请求，也只过滤一次的需求
 *
 * 继承自OncePerRequestFilter的Filter,采用@WebFilter以及Spring Bean的方式都是ok好使的
 * 采用@WebFilterinitFilterBean方法就只会被执行一次，但是，但是，但是此时@Autowaire自动注入就不好使了,需要自己去容器里拿
 *
 * Spring内置OncePerRequestFilter实现
 * {@link org.springframework.web.filter.CharacterEncodingFilter}
 * {@link org.springframework.web.filter.HiddenHttpMethodFilter}
 * {@link org.springframework.web.filter.HttpPutFormContentFilter}
 * {@link org.springframework.web.filter.FormContentFilter}
 * {@link org.springframework.web.filter.RequestContextFilter}  让你在一个请求的线程内，任意地方都可以获取到请求参数的相关信息，非常的方便;
 * 这里注意一个概念，缺省情况下，Servlet容器对一个请求的整个处理过程，是由同一个线程完成的，中途不会切换线程。但这个线程在处理完一个请求后，会被放回到线程池用于处理其他请求。
 * {@link org.springframework.web.multipart.support.MultipartFilter}
 * {@link org.springframework.web.filter.CorsFilter}
 */
public abstract class OncePerRequestFilter extends GenericFilterBean {

	/**
	 * Suffix that gets appended to the filter name for the
	 * "already filtered" request attribute.
	 * @see #getAlreadyFilteredAttributeName
	 * 已过滤过的过滤器的固定后缀名
	 */
	public static final String ALREADY_FILTERED_SUFFIX = ".FILTERED";


	/**
	 * This {@code doFilter} implementation stores a request attribute for
	 * "already filtered", proceeding without filtering again if the
	 * attribute is already there.
	 * @see #getAlreadyFilteredAttributeName
	 * @see #shouldNotFilter
	 * @see #doFilterInternal
	 */
	@Override
	public final void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		//只处理http请求
		if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
			throw new ServletException("OncePerRequestFilter just supports HTTP requests");
		}
		HttpServletRequest httpRequest = (HttpServletRequest) request;
		HttpServletResponse httpResponse = (HttpServletResponse) response;
		
		//判断这个请求是否需要执行过滤
		String alreadyFilteredAttributeName = getAlreadyFilteredAttributeName();
		boolean hasAlreadyFilteredAttribute = request.getAttribute(alreadyFilteredAttributeName) != null;

		if (skipDispatch(httpRequest) || shouldNotFilter(httpRequest)) {

			// Proceed without invoking this filter...
			// 直接放行，不执行此过滤器的过滤操作
			filterChain.doFilter(request, response);
		}
		else if (hasAlreadyFilteredAttribute) {

			if (DispatcherType.ERROR.equals(request.getDispatcherType())) {
				doFilterNestedErrorDispatch(httpRequest, httpResponse, filterChain);
				return;
			}
			// 直接放行，不执行此过滤器的过滤操作
			// Proceed without invoking this filter...
			filterChain.doFilter(request, response);
		}
		else {
			// Do invoke this filter...
			// 执行过滤，并且向请求域设置一个值，key就是生成的全局唯一的·alreadyFilteredAttributeName·
			request.setAttribute(alreadyFilteredAttributeName, Boolean.TRUE);
			try {
				//由子类自己去实现拦截的逻辑  注意 自己写时，filterChain.doFilter(request, response);这句代码不要忘了
				doFilterInternal(httpRequest, httpResponse, filterChain);
			}
			finally {
				// Remove the "already filtered" request attribute for this request.
				request.removeAttribute(alreadyFilteredAttributeName);
			}
		}
	}

	/**
	 * 这里很清楚的记录着  需要被跳过的请求们，这种请求直接就放行了
	 * @param request
	 * @return
	 */
	private boolean skipDispatch(HttpServletRequest request) {
		if (isAsyncDispatch(request) && shouldNotFilterAsyncDispatch()) {
			return true;
		}
		if (request.getAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE) != null && shouldNotFilterErrorDispatch()) {
			return true;
		}
		return false;
	}

	/**
	 * The dispatcher type {@code javax.servlet.DispatcherType.ASYNC} introduced
	 * in Servlet 3.0 means a filter can be invoked in more than one thread over
	 * the course of a single request. This method returns {@code true} if the
	 * filter is currently executing within an asynchronous dispatch.
	 * @param request the current request
	 * @since 3.2
	 * @see WebAsyncManager#hasConcurrentResult()
	 *
	 * 判断该请求是否是异步请求（Servlet 3.0后有异步请求,Spring MVC3.2开始）
	 */
	protected boolean isAsyncDispatch(HttpServletRequest request) {
		return WebAsyncUtils.getAsyncManager(request).hasConcurrentResult();
	}

	/**
	 * Whether request processing is in asynchronous mode meaning that the
	 * response will not be committed after the current thread is exited.
	 * @param request the current request
	 * @since 3.2
	 * @see WebAsyncManager#isConcurrentHandlingStarted()
	 */
	protected boolean isAsyncStarted(HttpServletRequest request) {
		return WebAsyncUtils.getAsyncManager(request).isConcurrentHandlingStarted();
	}

	/**
	 * Return the name of the request attribute that identifies that a request
	 * is already filtered.
	 * <p>The default implementation takes the configured name of the concrete filter
	 * instance and appends ".FILTERED". If the filter is not fully initialized,
	 * it falls back to its class name.
	 * @see #getFilterName
	 * @see #ALREADY_FILTERED_SUFFIX
	 *
	 * 相当于生成已过滤的过滤器的名字（全局唯一的）
	 */
	protected String getAlreadyFilteredAttributeName() {
		String name = getFilterName();
		if (name == null) {
			name = getClass().getName();
		}
		return name + ALREADY_FILTERED_SUFFIX;
	}

	/**
	 * Can be overridden in subclasses for custom filtering control,
	 * returning {@code true} to avoid filtering of the given request.
	 * <p>The default implementation always returns {@code false}.
	 * @param request current HTTP request
	 * @return whether the given request should <i>not</i> be filtered
	 * @throws ServletException in case of errors
	 *
	 * 可以人工直接返回true  那这个请求就肯定不会被过滤了~~~~
	 */
	protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
		return false;
	}

	/**
	 * The dispatcher type {@code javax.servlet.DispatcherType.ASYNC} introduced
	 * in Servlet 3.0 means a filter can be invoked in more than one thread
	 * over the course of a single request. Some filters only need to filter
	 * the initial thread (e.g. request wrapping) while others may need
	 * to be invoked at least once in each additional thread for example for
	 * setting up thread locals or to perform final processing at the very end.
	 * <p>Note that although a filter can be mapped to handle specific dispatcher
	 * types via {@code web.xml} or in Java through the {@code ServletContext},
	 * servlet containers may enforce different defaults with regards to
	 * dispatcher types. This flag enforces the design intent of the filter.
	 * <p>The default return value is "true", which means the filter will not be
	 * invoked during subsequent async dispatches. If "false", the filter will
	 * be invoked during async dispatches with the same guarantees of being
	 * invoked only once during a request within a single thread.
	 * @since 3.2
	 *
	 * 是否需要不过滤异步的请求（默认是不多次过滤异步请求的）
	 * javadoc：javax.servlet.DispatcherType.ASYNC的请求方式意味着可能在一个请求里这个过滤器会被多个不同线程调用多次，而这里返回true，就能保证只会被调用一次
	 */
	protected boolean shouldNotFilterAsyncDispatch() {
		return true;
	}

	/**
	 * Whether to filter error dispatches such as when the servlet container
	 * processes and error mapped in {@code web.xml}. The default return value
	 * is "true", which means the filter will not be invoked in case of an error
	 * dispatch.
	 * @since 3.2
	 */
	protected boolean shouldNotFilterErrorDispatch() {
		return true;
	}


	/**
	 * Same contract as for {@code doFilter}, but guaranteed to be
	 * just invoked once per request within a single request thread.
	 * See {@link #shouldNotFilterAsyncDispatch()} for details.
	 * <p>Provides HttpServletRequest and HttpServletResponse arguments instead of the
	 * default ServletRequest and ServletResponse ones.
	 */
	protected abstract void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException;

	/**
	 * Typically an ERROR dispatch happens after the REQUEST dispatch completes,
	 * and the filter chain starts anew. On some servers however the ERROR
	 * dispatch may be nested within the REQUEST dispatch, e.g. as a result of
	 * calling {@code sendError} on the response. In that case we are still in
	 * the filter chain, on the same thread, but the request and response have
	 * been switched to the original, unwrapped ones.
	 * <p>Sub-classes may use this method to filter such nested ERROR dispatches
	 * and re-apply wrapping on the request or response. {@code ThreadLocal}
	 * context, if any, should still be active as we are still nested within
	 * the filter chain.
	 * @since 5.1.9
	 */
	protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {

		filterChain.doFilter(request, response);
	}

}
