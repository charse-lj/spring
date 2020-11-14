/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.context.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.Lifecycle;
import org.springframework.context.LifecycleProcessor;
import org.springframework.context.Phased;
import org.springframework.context.SmartLifecycle;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Default implementation of the {@link LifecycleProcessor} strategy.
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @since 3.0
 */
public class DefaultLifecycleProcessor implements LifecycleProcessor, BeanFactoryAware {

	private final Log logger = LogFactory.getLog(getClass());

	private volatile long timeoutPerShutdownPhase = 30000;

	private volatile boolean running;

	@Nullable
	private volatile ConfigurableListableBeanFactory beanFactory;


	/**
	 * Specify the maximum time allotted in milliseconds for the shutdown of
	 * any phase (group of SmartLifecycle beans with the same 'phase' value).
	 * <p>The default value is 30 seconds.
	 */
	public void setTimeoutPerShutdownPhase(long timeoutPerShutdownPhase) {
		this.timeoutPerShutdownPhase = timeoutPerShutdownPhase;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		if (!(beanFactory instanceof ConfigurableListableBeanFactory)) {
			throw new IllegalArgumentException(
					"DefaultLifecycleProcessor requires a ConfigurableListableBeanFactory: " + beanFactory);
		}
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}

	private ConfigurableListableBeanFactory getBeanFactory() {
		ConfigurableListableBeanFactory beanFactory = this.beanFactory;
		Assert.state(beanFactory != null, "No BeanFactory available");
		return beanFactory;
	}


	// Lifecycle implementation

	/**
	 * Start all registered beans that implement {@link Lifecycle} and are <i>not</i>
	 * already running. Any bean that implements {@link SmartLifecycle} will be
	 * started within its 'phase', and all phases will be ordered from lowest to
	 * highest value. All beans that do not implement {@link SmartLifecycle} will be
	 * started in the default phase 0. A bean declared as a dependency of another bean
	 * will be started before the dependent bean regardless of the declared phase.
	 *
	 * Lifecycle的方法
	 * 启动和关闭调用的顺序是很重要的。如果两个对象之间存在依赖关系，依赖类要在其依赖类后启动，依赖类也要在其依赖类前停止
	 */
	@Override
	public void start() {
		// 传false，表示Bean一定会启动
		startBeans(false);
		this.running = true;
	}

	/**
	 * Stop all registered beans that implement {@link Lifecycle} and <i>are</i>
	 * currently running. Any bean that implements {@link SmartLifecycle} will be
	 * stopped within its 'phase', and all phases will be ordered from highest to
	 * lowest value. All beans that do not implement {@link SmartLifecycle} will be
	 * stopped in the default phase 0. A bean declared as dependent on another bean
	 * will be stopped before the dependency bean regardless of the declared phase.
	 */
	@Override
	public void stop() {
		stopBeans();
		this.running = false;
	}

	/**
	 * LifecycleProcessor的方法
	 * getLifecycleProcessor().onRefresh(); 这个方法才是容器启动时候自动会调用的，其余都不是
	 * 显然它默认只会执行实现了SmartLifecycle接口并且isAutoStartup = true的Bean的start方法
	 */
	@Override
	public void onRefresh() {
		startBeans(true);
		this.running = true;
	}

	/**
	 * 容器关闭的时候自动会调的
	 */
	@Override
	public void onClose() {
		stopBeans();
		this.running = false;
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}


	// Internal helpers

	/**
	 *
	 * @param autoStartupOnly 是否仅支持自动启动
	 * true：只支持伴随容器启动 （bean必须实现了`SmartLifecycle`接口且isAutoStartup为true才行）
	 *  false：表示无所谓。都会执行bean的start方法
	 */
	private void startBeans(boolean autoStartupOnly) {
		//拿到所有的实现了Lifecycle/SmartLifecycle的  已经在IOC容器里面的单例Bean们（备注：不包括自己this，也就是说处理器自己不包含进去）
		// 这里若我们自己没有定义过实现Lifecycle的Bean，这里就是空的
		Map<String, Lifecycle> lifecycleBeans = getLifecycleBeans();
		// key:如果实现了SmartLifeCycle，则为其getPhase方法返回的值，如果只是实现了Lifecycle，则返回0
		// value:相同phase的Lifecycle的集合，并将其封装到了一个LifecycleGroup中
		Map<Integer, LifecycleGroup> phases = new HashMap<>();
		// phases 这个Map，表示按照phase 值，吧这个Bean进行分组，最后分组执行
		lifecycleBeans.forEach((beanName, bean) -> {
			// 我们可以看到autoStartupOnly这个变量在上层传递过来的
			// 这个参数意味着是否只启动“自动”的Bean,这是什么意思呢？就是说，不需要手动调用容器的start方法
			// 从这里可以看出，实现了SmartLifecycle接口的类并且其isAutoStartup如果返回true的话，会在容器启动过程中自动调用，而仅仅实现了Lifecycle接口的类并不会被调用。
			// 如果我们去阅读容器的start方法的会发现，当调用链到达这个方法时，autoStartupOnly这个变量写死的为false
			// 若Bean实现了SmartLifecycle 接口并且标注是AutoStartup  或者  强制要求自动执行的autoStartupOnly = true
			if (!autoStartupOnly || (bean instanceof SmartLifecycle && ((SmartLifecycle) bean).isAutoStartup())) {
				//获取bean的优先级
				int phase = getPhase(bean);
				// 下面就是一个填充Map的操作，有的话add,没有的话直接new一个，比较简单
				LifecycleGroup group = phases.get(phase);
				if (group == null) {
					// LifecycleGroup构造函数需要四个参数
					// phase：代表这一组lifecycleBeans的执行阶段
					// timeoutPerShutdownPhase：因为lifecycleBean中的stop方法可以在另一个线程中运行，所以为了确保当前阶段的所有lifecycleBean都执行完，Spring使用了CountDownLatch，而为了防止无休止的等待下去，所有这里设置了一个等待的最大时间，默认为30秒
					// lifecycleBeans：所有的实现了Lifecycle的Bean
					// autoStartupOnly: 手动调用容器的start方法时，为false。容器启动阶段自动调用时为true,详细的含义在上面解释过了
					group = new LifecycleGroup(phase, this.timeoutPerShutdownPhase, lifecycleBeans, autoStartupOnly);
					phases.put(phase, group);
				}
				// 添加到phase 值相同的组  分组嘛
				group.add(beanName, bean);
			}
		});
		if (!phases.isEmpty()) {
			// 此处有个根据key从小到大的排序，然后一个个的调用他们的start方法
			List<Integer> keys = new ArrayList<>(phases.keySet());
			// 升序排序
			Collections.sort(keys);
			for (Integer key : keys) {
				// 这里调用LifecycleGroup#start()
				phases.get(key).start();
			}
		}
	}

	/**
	 * Start the specified bean as part of the given set of Lifecycle beans,
	 * making sure that any beans that it depends on are started first.
	 * @param lifecycleBeans a Map with bean name as key and Lifecycle instance as value
	 * @param beanName the name of the bean to start
	 */
	private void doStart(Map<String, ? extends Lifecycle> lifecycleBeans, String beanName, boolean autoStartupOnly) {
		Lifecycle bean = lifecycleBeans.remove(beanName);
		if (bean != null && bean != this) {
			// 获取这个Bean依赖的其它Bean,在启动时先启动其依赖的Bean
			String[] dependenciesForBean = getBeanFactory().getDependenciesForBean(beanName);
			for (String dependency : dependenciesForBean) {
				doStart(lifecycleBeans, dependency, autoStartupOnly);
			}
			if (!bean.isRunning() &&
					(!autoStartupOnly || !(bean instanceof SmartLifecycle) || ((SmartLifecycle) bean).isAutoStartup())) {
				if (logger.isTraceEnabled()) {
					logger.trace("Starting bean '" + beanName + "' of type [" + bean.getClass().getName() + "]");
				}
				try {
					bean.start();
				}
				catch (Throwable ex) {
					throw new ApplicationContextException("Failed to start bean '" + beanName + "'", ex);
				}
				if (logger.isDebugEnabled()) {
					logger.debug("Successfully started bean '" + beanName + "'");
				}
			}
		}
	}

	private void stopBeans() {
		Map<String, Lifecycle> lifecycleBeans = getLifecycleBeans();
		Map<Integer, LifecycleGroup> phases = new HashMap<>();
		lifecycleBeans.forEach((beanName, bean) -> {
			int shutdownPhase = getPhase(bean);
			LifecycleGroup group = phases.get(shutdownPhase);
			if (group == null) {
				group = new LifecycleGroup(shutdownPhase, this.timeoutPerShutdownPhase, lifecycleBeans, false);
				phases.put(shutdownPhase, group);
			}
			group.add(beanName, bean);
		});
		if (!phases.isEmpty()) {
			List<Integer> keys = new ArrayList<>(phases.keySet());
			keys.sort(Collections.reverseOrder());
			for (Integer key : keys) {
				phases.get(key).stop();
			}
		}
	}

	/**
	 * Stop the specified bean as part of the given set of Lifecycle beans,
	 * making sure that any beans that depends on it are stopped first.
	 * @param lifecycleBeans a Map with bean name as key and Lifecycle instance as value
	 * @param beanName the name of the bean to stop
	 */
	private void doStop(Map<String, ? extends Lifecycle> lifecycleBeans, final String beanName,
			final CountDownLatch latch, final Set<String> countDownBeanNames) {

		Lifecycle bean = lifecycleBeans.remove(beanName);
		if (bean != null) {
			// 获取这个Bean所被依赖的Bean,先对这些Bean进行stop操作
			String[] dependentBeans = getBeanFactory().getDependentBeans(beanName);
			for (String dependentBean : dependentBeans) {
				doStop(lifecycleBeans, dependentBean, latch, countDownBeanNames);
			}
			try {
				if (bean.isRunning()) {
					if (bean instanceof SmartLifecycle) {
						if (logger.isTraceEnabled()) {
							logger.trace("Asking bean '" + beanName + "' of type [" +
									bean.getClass().getName() + "] to stop");
						}
						countDownBeanNames.add(beanName);
						// 还记得到SmartLifecycle中的stop方法吗？里面接受了一个Runnable参数
						// 就是在这里地方传进去的。主要就是进行一个操作latch.countDown()，标记当前的lifeCycleBean的stop方法执行完成
						((SmartLifecycle) bean).stop(() -> {
							latch.countDown();
							countDownBeanNames.remove(beanName);
							if (logger.isDebugEnabled()) {
								logger.debug("Bean '" + beanName + "' completed its stop procedure");
							}
						});
					}
					else {
						if (logger.isTraceEnabled()) {
							logger.trace("Stopping bean '" + beanName + "' of type [" +
									bean.getClass().getName() + "]");
						}
						bean.stop();
						if (logger.isDebugEnabled()) {
							logger.debug("Successfully stopped bean '" + beanName + "'");
						}
					}
				}
				else if (bean instanceof SmartLifecycle) {
					// Don't wait for beans that aren't running...
					latch.countDown();
				}
			}
			catch (Throwable ex) {
				if (logger.isWarnEnabled()) {
					logger.warn("Failed to stop bean '" + beanName + "'", ex);
				}
			}
		}
	}


	// overridable hooks

	/**
	 * Retrieve all applicable Lifecycle beans: all singletons that have already been created,
	 * as well as all SmartLifecycle beans (even if they are marked as lazy-init).
	 * @return the Map of applicable beans, with bean names as keys and bean instances as values
	 *
	 * 获取所有实现了Lifecycle接口的Bean,如果采用了factroyBean的方式配置了一个LifecycleBean,那么factroyBean本身也要实现Lifecycle接口
	 * 配置为懒加载的LifecycleBean必须实现SmartLifeCycle才能被调用start方法
	 */
	protected Map<String, Lifecycle> getLifecycleBeans() {
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		Map<String, Lifecycle> beans = new LinkedHashMap<>();
		//获取所有实现了Lifecycle的beanNames
		String[] beanNames = beanFactory.getBeanNamesForType(Lifecycle.class, false, false);
		for (String beanName : beanNames) {
			//beanName标准化
			String beanNameToRegister = BeanFactoryUtils.transformedBeanName(beanName);
			//是否为BeanFacotry
			boolean isFactoryBean = beanFactory.isFactoryBean(beanNameToRegister);
			//是，在beanName前加'&'
			String beanNameToCheck = (isFactoryBean ? BeanFactory.FACTORY_BEAN_PREFIX + beanName : beanName);
			//包含该名字单例
			if ((beanFactory.containsSingleton(beanNameToRegister) &&
					//bean的类型是Lifecycle 或者SmartLifecycle
					(!isFactoryBean || matchesBeanType(Lifecycle.class, beanNameToCheck, beanFactory))) ||
					matchesBeanType(SmartLifecycle.class, beanNameToCheck, beanFactory)) {
				//获取对象
				Object bean = beanFactory.getBean(beanNameToCheck);
				if (bean != this && bean instanceof Lifecycle) {
					//收集
					beans.put(beanNameToRegister, (Lifecycle) bean);
				}
			}
		}
		return beans;
	}

	private boolean matchesBeanType(Class<?> targetType, String beanName, BeanFactory beanFactory) {
		Class<?> beanType = beanFactory.getType(beanName);
		return (beanType != null && targetType.isAssignableFrom(beanType));
	}

	/**
	 * Determine the lifecycle phase of the given bean.
	 * <p>The default implementation checks for the {@link Phased} interface, using
	 * a default of 0 otherwise. Can be overridden to apply other/further policies.
	 * @param bean the bean to introspect
	 * @return the phase (an integer value)
	 * @see Phased#getPhase()
	 * @see SmartLifecycle
	 */
	protected int getPhase(Lifecycle bean) {
		// 获取这个Bean执行的阶段，实际上就是调用SmartLifecycle中的getPhase方法
		// 如果没有实现SmartLifecycle，而是单纯的实现了Lifecycle，那么直接返回0
		return (bean instanceof Phased ? ((Phased) bean).getPhase() : 0);
	}


	/**
	 * Helper class for maintaining a group of Lifecycle beans that should be started
	 * and stopped together based on their 'phase' value (or the default value of 0).
	 */
	private class LifecycleGroup {

		private final int phase;

		private final long timeout;

		private final Map<String, ? extends Lifecycle> lifecycleBeans;

		private final boolean autoStartupOnly;

		private final List<LifecycleGroupMember> members = new ArrayList<>();

		private int smartMemberCount;

		public LifecycleGroup(
				int phase, long timeout, Map<String, ? extends Lifecycle> lifecycleBeans, boolean autoStartupOnly) {

			this.phase = phase;
			this.timeout = timeout;
			this.lifecycleBeans = lifecycleBeans;
			this.autoStartupOnly = autoStartupOnly;
		}

		public void add(String name, Lifecycle bean) {
			this.members.add(new LifecycleGroupMember(name, bean));
			if (bean instanceof SmartLifecycle) {
				this.smartMemberCount++;
			}
		}

		public void start() {
			if (this.members.isEmpty()) {
				return;
			}
			if (logger.isDebugEnabled()) {
				logger.debug("Starting beans in phase " + this.phase);
			}
			// 按照权重值进行排序  若没有实现Smart接口的  权重值都为0
			Collections.sort(this.members);
			for (LifecycleGroupMember member : this.members) {
				// 一次执行这些Bean的start方法（这里面逻辑就没啥好看的，只有一个考虑到getBeanFactory().dependenciesForBean控制Bean的依赖关系的）
				doStart(this.lifecycleBeans, member.name, this.autoStartupOnly);
			}
		}

		public void stop() {
			if (this.members.isEmpty()) {
				return;
			}
			if (logger.isDebugEnabled()) {
				logger.debug("Stopping beans in phase " + this.phase);
			}
			this.members.sort(Collections.reverseOrder());
			// 创建了一个CountDownLatch，需要等待的线程数量为当前阶段的所有ifecycleBean的数量
			CountDownLatch latch = new CountDownLatch(this.smartMemberCount);
			// stop方法可以异步执行，这里保存的是还没有执行完的lifecycleBean的名称
			Set<String> countDownBeanNames = Collections.synchronizedSet(new LinkedHashSet<>());
			// 所有lifecycleBeans的名字集合
			Set<String> lifecycleBeanNames = new HashSet<>(this.lifecycleBeans.keySet());
			for (LifecycleGroupMember member : this.members) {
				if (lifecycleBeanNames.contains(member.name)) {
					doStop(this.lifecycleBeans, member.name, latch, countDownBeanNames);
				}
				else if (member.bean instanceof SmartLifecycle) {
					// Already removed: must have been a dependent bean from another phase
					// 按理说，这段代码永远不会执行，可能是版本遗留的代码没有进行删除
					// 大家可以自行对比4.x的代码跟5.x的代码
					latch.countDown();
				}
			}
			try {
				// 最大等待时间30s，超时进行日志打印
				latch.await(this.timeout, TimeUnit.MILLISECONDS);
				if (latch.getCount() > 0 && !countDownBeanNames.isEmpty() && logger.isInfoEnabled()) {
					logger.info("Failed to shut down " + countDownBeanNames.size() + " bean" +
							(countDownBeanNames.size() > 1 ? "s" : "") + " with phase value " +
							this.phase + " within timeout of " + this.timeout + "ms: " + countDownBeanNames);
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}


	/**
	 * Adapts the Comparable interface onto the lifecycle phase model.
	 */
	private class LifecycleGroupMember implements Comparable<LifecycleGroupMember> {

		private final String name;

		private final Lifecycle bean;

		LifecycleGroupMember(String name, Lifecycle bean) {
			this.name = name;
			this.bean = bean;
		}

		@Override
		public int compareTo(LifecycleGroupMember other) {
			int thisPhase = getPhase(this.bean);
			int otherPhase = getPhase(other.bean);
			return Integer.compare(thisPhase, otherPhase);
		}
	}

}
