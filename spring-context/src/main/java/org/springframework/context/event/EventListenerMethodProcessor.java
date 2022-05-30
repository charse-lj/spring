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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.aop.scope.ScopedObject;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.SpringProperties;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers {@link EventListener} methods as individual {@link ApplicationListener} instances.
 * Implements {@link BeanFactoryPostProcessor} (as of 5.1) primarily for early retrieval,
 * avoiding AOP checks for this processor bean and its {@link EventListenerFactory} delegates.
 *
 * @author Stephane Nicoll
 * @author Juergen Hoeller
 * @author Sebastien Deleuze
 * @since 4.2
 * @see EventListenerFactory
 * @see DefaultEventListenerFactory
 *
 * 事件收集器：处理EventListener注解然后把它注册为一个特别的ApplicationListener的处理器
 * 当然还有一个EventListenerFactory(DefaultEventListenerFactory)
 *
 * EventListenerMethodProcessor：
 * 它是一个SmartInitializingSingleton，所以他会在preInstantiateSingletons()的最后一步执行
 */
public class EventListenerMethodProcessor
		implements SmartInitializingSingleton, ApplicationContextAware, BeanFactoryPostProcessor {

	/**
	 * Boolean flag controlled by a {@code spring.spel.ignore} system property that instructs Spring to
	 * ignore SpEL, i.e. to not initialize the SpEL infrastructure.
	 * <p>The default is "false".
	 */
	private static final boolean shouldIgnoreSpel = SpringProperties.getFlag("spring.spel.ignore");


	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	private ConfigurableApplicationContext applicationContext;

	@Nullable
	private ConfigurableListableBeanFactory beanFactory;

	@Nullable
	private List<EventListenerFactory> eventListenerFactories;

	/**
	 * 解析注解中的Conditon的
 	 */
	@Nullable
	private final EventExpressionEvaluator evaluator;


	private final Set<Class<?>> nonAnnotatedClasses = Collections.newSetFromMap(new ConcurrentHashMap<>(64));


	public EventListenerMethodProcessor() {
		if (shouldIgnoreSpel) {
			this.evaluator = null;
		}
		else {
			this.evaluator = new EventExpressionEvaluator();
		}
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		Assert.isTrue(applicationContext instanceof ConfigurableApplicationContext,
				"ApplicationContext does not implement ConfigurableApplicationContext");
		this.applicationContext = (ConfigurableApplicationContext) applicationContext;
	}

	/**
	 * 这个方法是BeanFactoryPostProcessor的方法，它在容器的BeanFactory准备完成后，会执行此后置处理器
	 * @param beanFactory the bean factory used by the application context
	 */
	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;

		Map<String, EventListenerFactory> beans = beanFactory.getBeansOfType(EventListenerFactory.class, false, false);
		List<EventListenerFactory> factories = new ArrayList<>(beans.values());
		// 会根据@Order进行排序~~~~
		AnnotationAwareOrderComparator.sort(factories);
		this.eventListenerFactories = factories;
	}


	@Override
	public void afterSingletonsInstantiated() {
		// 从容器里获得所有的EventListenerFactory，它是用来后面处理标注了@EventListener方法的工厂（Spring默认放置的是DefaultEventListenerFactory，我们也可以继续放  支持@Order等注解）
		ConfigurableListableBeanFactory beanFactory = this.beanFactory;
		Assert.state(this.beanFactory != null, "No ConfigurableListableBeanFactory set");
		// 这里厉害了，用Object.class 是拿出容器里面所有的Bean定义~~~  一个一个的检查
		String[] beanNames = beanFactory.getBeanNamesForType(Object.class);
		for (String beanName : beanNames) {
			// 不处理Scope作用域代理的类。 和@Scope类似相关
			if (!ScopedProxyUtils.isScopedTarget(beanName)) {
				Class<?> type = null;
				try {
					// 防止是代理，把真实的类型拿出来
					type = AutoProxyUtils.determineTargetClass(beanFactory, beanName);
				}
				catch (Throwable ex) {
					// An unresolvable bean type, probably from a lazy bean - let's ignore it.
					if (logger.isDebugEnabled()) {
						logger.debug("Could not resolve target class for bean with name '" + beanName + "'", ex);
					}
				}
				// 对专门的作用域对象进行兼容~~~~（绝大部分都用不着）
				if (type != null) {
					if (ScopedObject.class.isAssignableFrom(type)) {
						try {
							Class<?> targetClass = AutoProxyUtils.determineTargetClass(
									beanFactory, ScopedProxyUtils.getTargetBeanName(beanName));
							if (targetClass != null) {
								type = targetClass;
							}
						}
						catch (Throwable ex) {
							// An invalid scoped proxy arrangement - let's ignore it.
							if (logger.isDebugEnabled()) {
								logger.debug("Could not resolve target bean for scoped proxy '" + beanName + "'", ex);
							}
						}
					}
					try {
						// 真正处理这个Bean里面的方法们。。。
						processBean(beanName, type);
					}
					catch (Throwable ex) {
						throw new BeanInitializationException("Failed to process @EventListener " +
								"annotation on bean with name '" + beanName + "'", ex);
					}
				}
			}
		}
	}

	private void processBean(final String beanName, final Class<?> targetType) {
		// 缓存下没有被注解过的Class，这样再次解析此Class就不用再处理了
		//这是为了加速父子容器的情况  做的特别优化
		if (!this.nonAnnotatedClasses.contains(targetType) &&
				AnnotationUtils.isCandidateClass(targetType, EventListener.class) &&
				!isSpringContainerClass(targetType)) {

			Map<Method, EventListener> annotatedMethods = null;
			try {
				// 这可以说是核心方法，就是找到这个Class里面被标注此注解的Methods们
				// 本类、父类、接口
				annotatedMethods = MethodIntrospector.selectMethods(targetType,
						(MethodIntrospector.MetadataLookup<EventListener>) method ->
								AnnotatedElementUtils.findMergedAnnotation(method, EventListener.class));
			}
			catch (Throwable ex) {
				// An unresolvable type in a method signature, probably from a lazy bean - let's ignore it.
				if (logger.isDebugEnabled()) {
					logger.debug("Could not resolve methods for bean with name '" + beanName + "'", ex);
				}
			}
			// 若一个都没找到，那就标注此类没有标注注解，那就标记一下此类  然后拉到算了  输出一句trace日志足矣
			if (CollectionUtils.isEmpty(annotatedMethods)) {
				this.nonAnnotatedClasses.add(targetType);
				if (logger.isTraceEnabled()) {
					logger.trace("No @EventListener annotations found on bean class: " + targetType.getName());
				}
			}
			else {
				//若存在对应的@EventListener标注的方法，那就走这里
				// 最终此Method是交给`EventListenerFactory`这个工厂，适配成一个ApplicationListener的
				// 适配类为ApplicationListenerMethodAdapter，它也是个ApplicationListener
				// Non-empty set of methods
				ConfigurableApplicationContext context = this.applicationContext;
				Assert.state(context != null, "No ApplicationContext set");
				List<EventListenerFactory> factories = this.eventListenerFactories;
				Assert.state(factories != null, "EventListenerFactory List not initialized");
				// 处理这些带有@EventListener注解的方法们
				for (Method method : annotatedMethods.keySet()) {
					// 这里面注意：拿到每个EventListenerFactory (一般情况下只有DefaultEventListenerFactory,但是若是注解驱动的事务还会有它：TransactionalEventListenerFactory)
					for (EventListenerFactory factory : factories) {
						// 加工的工厂类也可能有多个，但默认只有Spring注册给我们的一个
						// supportsMethod表示是否支持去处理此方法（因为我们可以定义处理器，只处理指定的Method都是欧克的）  Spring默认实现永远返回true（事务相关的除外，请注意工厂的顺序）
						if (factory.supportsMethod(method)) {
							// 简单的说，就是把这个方法弄成一个可以执行的方法（主要和访问权限有关）
							// 这里注意：若你是JDK的代理类，请不要在实现类里书写@EventListener注解的监听器，否则会报错的。（CGLIB代理的木关系） 原因上面已经说明了
							Method methodToUse = AopUtils.selectInvocableMethod(method, context.getType(beanName));
							// 把这个方法包装成一个监听器ApplicationListener（ApplicationListenerMethodAdapter类型）
							// 通过工厂创建出来的监听器  也给添加进context里面去~~~~~
							ApplicationListener<?> applicationListener =
									factory.createApplicationListener(beanName, targetType, methodToUse);
							if (applicationListener instanceof ApplicationListenerMethodAdapter) {
								// 这个init方法是把ApplicationContext注入进去
								((ApplicationListenerMethodAdapter) applicationListener).init(context, this.evaluator);
							}
							// 添加进去  管理起来
							context.addApplicationListener(applicationListener);
							// 这个break意思是：只要有一个工厂处理了这个方法，接下来的工厂就不需要再处理此方法了~~~~（所以工厂之间的排序也比较重要）
							break;
						}
					}
				}
				if (logger.isDebugEnabled()) {
					logger.debug(annotatedMethods.size() + " @EventListener methods processed on bean '" +
							beanName + "': " + annotatedMethods);
				}
			}
		}
	}

	/**
	 * Determine whether the given class is an {@code org.springframework}
	 * bean class that is not annotated as a user or test {@link Component}...
	 * which indicates that there is no {@link EventListener} to be found there.
	 * @since 5.1
	 */
	private static boolean isSpringContainerClass(Class<?> clazz) {
		return (clazz.getName().startsWith("org.springframework.") &&
				!AnnotatedElementUtils.isAnnotated(ClassUtils.getUserClass(clazz), Component.class));
	}

}
