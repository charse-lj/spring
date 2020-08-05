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

package org.springframework.context.event;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.expression.AnnotatedElementKey;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.ReactiveAdapter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.concurrent.ListenableFuture;

/**
 * {@link GenericApplicationListener} adapter that delegates the processing of
 * an event to an {@link EventListener} annotated method.
 *
 * <p>Delegates to {@link #processEvent(ApplicationEvent)} to give subclasses
 * a chance to deviate from the default. Unwraps the content of a
 * {@link PayloadApplicationEvent} if necessary to allow a method declaration
 * to define any arbitrary event type. If a condition is defined, it is
 * evaluated prior to invoking the underlying method.
 *
 * @author Stephane Nicoll
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 4.2
 *
 * Spring的使用@EventListener监听事件。若监听方法有返回值，那将会把这个返回值当作事件源，一直发送下去，直到返回void或者null停止
 * @EventListener(value = {ContextRefreshedEvent.class})
 *     public List<Child> handle(Object o) {
 *         List<Child> childList = new ArrayList<>();
 *         childList.add(new Child("1"));
 *         childList.add(new Child("2"));
 *         return childList;
 *     }
 *
 *     // 因为上个方法有返回  所以事件会传递到此处
 *     @EventListener(Child.class)
 *     public void handChild(Child c) {
 *         System.out.println(c.getName() + " 发来了事件");
 *     }
 *
 * 输出：
 * 1 发来了事件
 * 2 发来了事件
 */
public class ApplicationListenerMethodAdapter implements GenericApplicationListener {

	private static final boolean reactiveStreamsPresent = ClassUtils.isPresent(
			"org.reactivestreams.Publisher", ApplicationListenerMethodAdapter.class.getClassLoader());


	protected final Log logger = LogFactory.getLog(getClass());

	private final String beanName;

	private final Method method;

	private final Method targetMethod;

	private final AnnotatedElementKey methodKey;

	private final List<ResolvableType> declaredEventTypes;

	@Nullable
	private final String condition;

	private final int order;

	@Nullable
	private ApplicationContext applicationContext;

	//事件表达式处理器  默认使用的SpEL去解析  只是对它进行了增强
	@Nullable
	private EventExpressionEvaluator evaluator;


	public ApplicationListenerMethodAdapter(String beanName, Class<?> targetClass, Method method) {
		this.beanName = beanName;
		this.method = BridgeMethodResolver.findBridgedMethod(method);
		this.targetMethod = (!Proxy.isProxyClass(targetClass) ?
				AopUtils.getMostSpecificMethod(method, targetClass) : this.method);
		this.methodKey = new AnnotatedElementKey(this.targetMethod, targetClass);

		// 处理@EventListener注解信息  备注：至少指定一个监听类型
		EventListener ann = AnnotatedElementUtils.findMergedAnnotation(this.targetMethod, EventListener.class);
		this.declaredEventTypes = resolveDeclaredEventTypes(method, ann);
		// 拿到条件信息  SpEL中有用
		this.condition = (ann != null ? ann.condition() : null);
		// 从此处也能看出，它是支持在方法上标注@Order来控制执行顺序的
		this.order = resolveOrder(this.targetMethod);
	}

	private static List<ResolvableType> resolveDeclaredEventTypes(Method method, @Nullable EventListener ann) {
		int count = method.getParameterCount();
		if (count > 1) {
			throw new IllegalStateException(
					"Maximum one parameter is allowed for event listener method: " + method);
		}

		if (ann != null) {
			Class<?>[] classes = ann.classes();
			if (classes.length > 0) {
				List<ResolvableType> types = new ArrayList<>(classes.length);
				for (Class<?> eventType : classes) {
					types.add(ResolvableType.forClass(eventType));
				}
				return types;
			}
		}

		if (count == 0) {
			throw new IllegalStateException(
					"Event parameter is mandatory for event listener method: " + method);
		}
		return Collections.singletonList(ResolvableType.forMethodParameter(method, 0));
	}

	private static int resolveOrder(Method method) {
		Order ann = AnnotatedElementUtils.findMergedAnnotation(method, Order.class);
		return (ann != null ? ann.value() : 0);
	}


	/**
	 * Initialize this instance.
	 */
	void init(ApplicationContext applicationContext, @Nullable EventExpressionEvaluator evaluator) {
		this.applicationContext = applicationContext;
		this.evaluator = evaluator;
	}


	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		processEvent(event);
	}

	// 判断该处理器  是否支持当前类型的事件
	// 判断思路很简单：类型匹配上了 就表示可以处理这个事件（支持事件的泛型依赖匹配~~~）
	// 关于condition 是在process处理的时候会生效的
	@Override
	public boolean supportsEventType(ResolvableType eventType) {
		for (ResolvableType declaredEventType : this.declaredEventTypes) {
			if (declaredEventType.isAssignableFrom(eventType)) {
				return true;
			}
			if (PayloadApplicationEvent.class.isAssignableFrom(eventType.toClass())) {
				ResolvableType payloadType = eventType.as(PayloadApplicationEvent.class).getGeneric();
				if (declaredEventType.isAssignableFrom(payloadType)) {
					return true;
				}
			}
		}
		return eventType.hasUnresolvableGenerics();
	}

	@Override
	public boolean supportsSourceType(@Nullable Class<?> sourceType) {
		return true;
	}

	@Override
	public int getOrder() {
		return this.order;
	}


	/**
	 * Process the specified {@link ApplicationEvent}, checking if the condition
	 * matches and handling a non-null result, if any.
	 */
	public void processEvent(ApplicationEvent event) {
		// 解析参数，很简单  主要是兼容PayloadApplicationEvent 把事件拿出来
		// 返回的数组要么为[]，总之最多只有一个参数  就是事件本身
		//事件类型是PayloadApplicationEvent,那就把.getPayload()，否则就是event本身喽
		Object[] args = resolveArguments(event);
		//  此处是我们本文的重点，就是解析condition 条件的地方，下面专门讨论，现在继续往下走
		// 总之就是根据事件源、绝大多数情况下args里面装的就是这个event~~~~~
		//解析condition表达式（注意，此处把args传进去了） 因此我们表达式里是可以用这个参数的哦
		if (shouldHandle(event, args)) {
			// 这一句非常的简单  就是调用此方法Method~
			// 就是执行目标方法，我们一般返回值都是void，所以就是null
			// 但是，但是，但是注意了，此处若返回的不是null，还有处理~~~~非常给力：
			Object result = doInvoke(args);
			// 这一步就是@EventListener最大的优势。如果它的返回值不为null，那么它可以行使事件链，可以继续发布事件
			// 把返回值当作事件继续publish（返回值可以是个Object，最终被包装成payload事件~~~~）
			if (result != null) {
				// 如果返回值是数组或者Collection，会把里面内容当作事件循环publishEvent
				// 如果就是个POJO，那就直接publish
				// 事件的传递性 就这么的来了，强大啊
				handleResult(result);
			}
			else {
				logger.trace("No result object given - no result to handle");
			}
		}
	}

	/**
	 * Resolve the method arguments to use for the specified {@link ApplicationEvent}.
	 * <p>These arguments will be used to invoke the method handled by this instance.
	 * Can return {@code null} to indicate that no suitable arguments could be resolved
	 * and therefore the method should not be invoked at all for the specified event.
	 */
	@Nullable
	protected Object[] resolveArguments(ApplicationEvent event) {
		ResolvableType declaredEventType = getResolvableType(event);
		if (declaredEventType == null) {
			return null;
		}
		if (this.method.getParameterCount() == 0) {
			return new Object[0];
		}
		Class<?> declaredEventClass = declaredEventType.toClass();
		if (!ApplicationEvent.class.isAssignableFrom(declaredEventClass) &&
				event instanceof PayloadApplicationEvent) {
			Object payload = ((PayloadApplicationEvent<?>) event).getPayload();
			if (declaredEventClass.isInstance(payload)) {
				return new Object[] {payload};
			}
		}
		return new Object[] {event};
	}

	protected void handleResult(Object result) {
		if (reactiveStreamsPresent && new ReactiveResultHandler().subscribeToPublisher(result)) {
			if (logger.isTraceEnabled()) {
				logger.trace("Adapted to reactive result: " + result);
			}
		}
		else if (result instanceof CompletionStage) {
			((CompletionStage<?>) result).whenComplete((event, ex) -> {
				if (ex != null) {
					handleAsyncError(ex);
				}
				else if (event != null) {
					publishEvent(event);
				}
			});
		}
		else if (result instanceof ListenableFuture) {
			((ListenableFuture<?>) result).addCallback(this::publishEvents, this::handleAsyncError);
		}
		else {
			publishEvents(result);
		}
	}

	private void publishEvents(Object result) {
		if (result.getClass().isArray()) {
			Object[] events = ObjectUtils.toObjectArray(result);
			for (Object event : events) {
				publishEvent(event);
			}
		}
		else if (result instanceof Collection<?>) {
			Collection<?> events = (Collection<?>) result;
			for (Object event : events) {
				publishEvent(event);
			}
		}
		else {
			publishEvent(result);
		}
	}

	private void publishEvent(@Nullable Object event) {
		if (event != null) {
			Assert.notNull(this.applicationContext, "ApplicationContext must not be null");
			this.applicationContext.publishEvent(event);
		}
	}

	protected void handleAsyncError(Throwable t) {
		logger.error("Unexpected error occurred in asynchronous listener", t);
	}

	private boolean shouldHandle(ApplicationEvent event, @Nullable Object[] args) {
		if (args == null) {
			return false;
		}
		// condition默认是空串  只有配置了才会去执行~~~  是用的解析器是EventExpressionEvaluator
		String condition = getCondition();
		if (StringUtils.hasText(condition)) {
			Assert.notNull(this.evaluator, "EventExpressionEvaluator must not be null");
			// 最终委托给EventExpressionEvaluator去解析
			// 备注EventExpressionEvaluator是个内部使用的类，只有此处解析用到了~~~
			return this.evaluator.condition(
					condition, event, this.targetMethod, this.methodKey, args, this.applicationContext);
		}
		return true;
	}

	/**
	 * Invoke the event listener method with the given argument values.
	 */
	@Nullable
	protected Object doInvoke(Object... args) {
		Object bean = getTargetBean();
		// Detect package-protected NullBean instance through equals(null) check
		if (bean.equals(null)) {
			return null;
		}

		ReflectionUtils.makeAccessible(this.method);
		try {
			return this.method.invoke(bean, args);
		}
		catch (IllegalArgumentException ex) {
			assertTargetBean(this.method, bean, args);
			throw new IllegalStateException(getInvocationErrorMessage(bean, ex.getMessage(), args), ex);
		}
		catch (IllegalAccessException ex) {
			throw new IllegalStateException(getInvocationErrorMessage(bean, ex.getMessage(), args), ex);
		}
		catch (InvocationTargetException ex) {
			// Throw underlying exception
			Throwable targetException = ex.getTargetException();
			if (targetException instanceof RuntimeException) {
				throw (RuntimeException) targetException;
			}
			else {
				String msg = getInvocationErrorMessage(bean, "Failed to invoke event listener method", args);
				throw new UndeclaredThrowableException(targetException, msg);
			}
		}
	}

	/**
	 * Return the target bean instance to use.
	 */
	protected Object getTargetBean() {
		Assert.notNull(this.applicationContext, "ApplicationContext must no be null");
		return this.applicationContext.getBean(this.beanName);
	}

	/**
	 * Return the condition to use.
	 * <p>Matches the {@code condition} attribute of the {@link EventListener}
	 * annotation or any matching attribute on a composed annotation that
	 * is meta-annotated with {@code @EventListener}.
	 */
	@Nullable
	protected String getCondition() {
		return this.condition;
	}

	/**
	 * Add additional details such as the bean type and method signature to
	 * the given error message.
	 * @param message error message to append the HandlerMethod details to
	 */
	protected String getDetailedErrorMessage(Object bean, String message) {
		StringBuilder sb = new StringBuilder(message).append("\n");
		sb.append("HandlerMethod details: \n");
		sb.append("Bean [").append(bean.getClass().getName()).append("]\n");
		sb.append("Method [").append(this.method.toGenericString()).append("]\n");
		return sb.toString();
	}

	/**
	 * Assert that the target bean class is an instance of the class where the given
	 * method is declared. In some cases the actual bean instance at event-
	 * processing time may be a JDK dynamic proxy (lazy initialization, prototype
	 * beans, and others). Event listener beans that require proxying should prefer
	 * class-based proxy mechanisms.
	 */
	private void assertTargetBean(Method method, Object targetBean, Object[] args) {
		Class<?> methodDeclaringClass = method.getDeclaringClass();
		Class<?> targetBeanClass = targetBean.getClass();
		if (!methodDeclaringClass.isAssignableFrom(targetBeanClass)) {
			String msg = "The event listener method class '" + methodDeclaringClass.getName() +
					"' is not an instance of the actual bean class '" +
					targetBeanClass.getName() + "'. If the bean requires proxying " +
					"(e.g. due to @Transactional), please use class-based proxying.";
			throw new IllegalStateException(getInvocationErrorMessage(targetBean, msg, args));
		}
	}

	private String getInvocationErrorMessage(Object bean, String message, Object[] resolvedArgs) {
		StringBuilder sb = new StringBuilder(getDetailedErrorMessage(bean, message));
		sb.append("Resolved arguments: \n");
		for (int i = 0; i < resolvedArgs.length; i++) {
			sb.append("[").append(i).append("] ");
			if (resolvedArgs[i] == null) {
				sb.append("[null] \n");
			}
			else {
				sb.append("[type=").append(resolvedArgs[i].getClass().getName()).append("] ");
				sb.append("[value=").append(resolvedArgs[i]).append("]\n");
			}
		}
		return sb.toString();
	}

	@Nullable
	private ResolvableType getResolvableType(ApplicationEvent event) {
		ResolvableType payloadType = null;
		if (event instanceof PayloadApplicationEvent) {
			PayloadApplicationEvent<?> payloadEvent = (PayloadApplicationEvent<?>) event;
			ResolvableType eventType = payloadEvent.getResolvableType();
			if (eventType != null) {
				payloadType = eventType.as(PayloadApplicationEvent.class).getGeneric();
			}
		}
		for (ResolvableType declaredEventType : this.declaredEventTypes) {
			Class<?> eventClass = declaredEventType.toClass();
			if (!ApplicationEvent.class.isAssignableFrom(eventClass) &&
					payloadType != null && declaredEventType.isAssignableFrom(payloadType)) {
				return declaredEventType;
			}
			if (eventClass.isInstance(event)) {
				return declaredEventType;
			}
		}
		return null;
	}


	@Override
	public String toString() {
		return this.method.toGenericString();
	}


	private class ReactiveResultHandler {

		public boolean subscribeToPublisher(Object result) {
			ReactiveAdapter adapter = ReactiveAdapterRegistry.getSharedInstance().getAdapter(result.getClass());
			if (adapter != null) {
				adapter.toPublisher(result).subscribe(new EventPublicationSubscriber());
				return true;
			}
			return false;
		}
	}


	private class EventPublicationSubscriber implements Subscriber<Object> {

		@Override
		public void onSubscribe(Subscription s) {
			s.request(Integer.MAX_VALUE);
		}

		@Override
		public void onNext(Object o) {
			publishEvents(o);
		}

		@Override
		public void onError(Throwable t) {
			handleAsyncError(t);
		}

		@Override
		public void onComplete() {
		}
	}

}
