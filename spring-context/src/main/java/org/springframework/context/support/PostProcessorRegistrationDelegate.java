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

package org.springframework.context.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.lang.Nullable;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}


	/**
	 * 那为什么逻辑要先执行postProcessBeanDefinitionRegistry然后在执行postProcessBeanFactory呢？
	 * 因为postProcessBeanDefinitionRegistry是用来创建bean定义的，
	 * 而postProcessBeanFactory是修改BeanFactory，当然postProcessBeanFactory也可以修改bean定义的。
	 * 为了保证在修改之前所有的bean定义的都存在，所以优先执行postProcessBeanDefinitionRegistry。
	 * 如不是以上顺序，会出先再修改某个bean定义的报错，因为此bean定义的还没有被创建。
	 * @param beanFactory
	 * @param beanFactoryPostProcessors
	 */
	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		// 这个doc说明很清楚：不管怎么样，先执行BeanDefinitionRegistryPostProcessors
		// BeanDefinitionRegistryPostProcessors 为 BeanFactoryPostProcessor 的子接口
		// BeanDefinitionRegistryPostProcessors 新增了方法：void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)
		// BeanFactoryPostProcessor 的方法为: void postProcessBeanFactory(BeanDefinitionRegistry registry)
		// 所以BeanDefinitionRegistryPostProcessors，它可以让我们介入，改变Bean的一些定义信息
		Set<String> processedBeans = new HashSet<>();

		// beanFactory 是BeanDefinitionRegistry子类,才能管理BeanDefinition
		if (beanFactory instanceof BeanDefinitionRegistry) {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;

			//自定义的BeanFactoryPostProcessor执行并分组
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();

			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
					BeanDefinitionRegistryPostProcessor registryProcessor =
							(BeanDefinitionRegistryPostProcessor) postProcessor;
					// 执行postProcessBeanDefinitionRegistry()方法
					registryProcessor.postProcessBeanDefinitionRegistry(registry);
					//放入容器 -->还有BeanFactoryPostProcessor的postProcessBeanFactory()方法未执行,所以需要保存等待合适机会执行
					registryProcessors.add(registryProcessor);
				}
				else {
					regularPostProcessors.add(postProcessor);
				}
			}

			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the bean factory post-processors apply to them!
			// Separate between BeanDefinitionRegistryPostProcessors that implement
			// PriorityOrdered, Ordered, and the rest.
			// 保存当前需要执行的实现了BeanDefinitionRegistryPostProcessor接口的后置处理器
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

			// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
			// 接下来，就是去执行Spring容器里面的一些 BeanDefinitionRegistryPostProcessor了。他们顺序doc里也写得很清楚：
			// 先执行实现了PriorityOrdered接口的，然后是Ordered接口的，最后执行剩下的
			//这里进行了bd的合并
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				// 判断这个类是否还实现了PriorityOrdered接口
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					// 如果满足条件，会将其创建出来，同时添加到集合中
					// 正常情况下，只会有一个，就是Spring容器自己提供的ConfigurationClassPostProcessor,Spring通过这个类完成了扫描以及BeanDefinition的功能
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}
			// 排序 容器中 BeanDefinitionRegistryPostProcessor
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			// 此处缓冲起来（手动设置+容器查找的需要注意的是BeanDefinitionRegistryPostProcessor，是排序后，再放进去的 这样是最好的）
			registryProcessors.addAll(currentRegistryProcessors);
			//执行 容器中 BeanDefinitionRegistryPostProcessor#postProcessBeanDefinitionRegistry()
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
			//清除容器
			currentRegistryProcessors.clear();

			// Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
			//处理order,方法同上
			//这里又进行了一个bd的合并
			// 这里重新获取实现了BeanDefinitionRegistryPostProcesso接口的后置处理器的名字，思考一个问题：为什么之前获取了一次不能直接用呢？还需要获取一次呢？
			// 这是因为，在我们上面执行过了BeanDefinitionRegistryPostProcessor中，可以在某个类中，我们扩展的时候又注册了一个实现了BeanDefinitionRegistryPostProcessor接口的后置处理器
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}
			// 根据ordered接口进行排序
			sortPostProcessors(currentRegistryProcessors, beanFactory);

			// 将当前将要执行的currentRegistryProcessors全部添加到registryProcessors这个集合中
			registryProcessors.addAll(currentRegistryProcessors);

			// 执行后置处理器的逻辑，这里只会执行BeanDefinitionRegistryPostProcessor接口的postProcessBeanDefinitionRegistry方法
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);

			// 清空集合
			currentRegistryProcessors.clear();

			// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
			// 接下来这段代码是为了确认所有实现了BeanDefinitionRegistryPostProcessor的后置处理器能够执行完，之所有要一个循环中执行，也是为了防止在执行过程中注册了新的BeanDefinitionRegistryPostProcessor
			// 在执行postProcessBeanFactory并没有进行类似的查找。这是为什么呢？Spring在设计时postProcessBeanFactory这个方法不是用于重新注册一个Bean的，而是修改
			// postProcessBeanDefinitionRegistry这个方法,允许我后置处理器执行时添加更多的BeanDefinition
			boolean reiterate = true;
			while (reiterate) {
				reiterate = false;
				//这里又进行了一个bd的合并
				// 获取普通的BeanDefinitionRegistryPostProcessor，不需要实现PriorityOrdered或者Ordered接口
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				for (String ppName : postProcessorNames) {
					//processedBeans 缓存的是从容器中查找的带有@PriorityOrdered、@Order注解的BeanDefinitionRegistryPostProcessor
					if (!processedBeans.contains(ppName)) {
						// 只要发现有一个需要执行了的后置处理器，就需要再次循环，因为执行了这个后置处理可能会注册新的BeanDefinitionRegistryPostProcessor
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						processedBeans.add(ppName);
						reiterate = true;
					}
				}
				//currentRegistryProcessors 缓存的是不带排序注解的BeanDefinitionRegistryPostProcessor，执行流程同上
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				registryProcessors.addAll(currentRegistryProcessors);
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry);
				currentRegistryProcessors.clear();
			}

			// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
			// 现在，这里很明显：去执行BeanDefinitionRegistryPostProcessor的postProcessBeanFactory方法
			// 以及 顶层接口BeanFactoryPostProcessor的postProcessBeanFactory方法
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);

			//手动设置的普通BeanFactoryPostProcessor的postProcessBeanFactory方法
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
		}

		else {
			// 正常情况下，进不来这个判断，不用考虑
			// Invoke factory processors registered with the context instance.
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		// 获取所有实现了BeanFactoryPostProcessor接口的后置处理器，这里会获取到已经执行过的后置处理器，所以后面的代码会区分已经执行过或者未执行过
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		// 下面就是开始执行容器的BeanFactoryPostProcessor 基本也是按照上面的顺序来执行的
		// 保存直接实现了BeanFactoryPostProcessor接口和PriorityOrdered接口的后置处理器
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		// 保存直接实现了BeanFactoryPostProcessor接口和Ordered接口的后置处理器
		List<String> orderedPostProcessorNames = new ArrayList<>();
		// 保存直接实现了BeanFactoryPostProcessor接口的后置处理器，不包括那些实现了排序接口的类
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
				// 这里面注意，已经执行过的后置处理器，就不要再执行了
			}
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
		// 先执行实现了BeanFactoryPostProcessor接口和PriorityOrdered接口的后置处理器
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		// 再执行实现了BeanFactoryPostProcessor接口和Ordered接口的后置处理器
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String postProcessorName : orderedPostProcessorNames) {
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		sortPostProcessors(orderedPostProcessors, beanFactory);
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

		// Finally, invoke all other BeanFactoryPostProcessors.
		// 最后执行BeanFactoryPostProcessor接口的后置处理器，不包括那些实现了排序接口的类
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, e.g. replacing placeholders in values...
		// 将合并的BeanDefinition清空，这是因为我们在执行后置处理器时，可能已经修改过了BeanDefinition中的属性，所以需要清空，以便于重新合并
		beanFactory.clearMetadataCache();
	}

	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {

		// 从所与Bean定义中提取出BeanPostProcessor类型的Bean，显然，最初的6个bean，有三个是BeanPostProcessor：
		/** {@link AutowiredAnnotationBeanPostProcessor}  {@linkRequiredAnnotationBeanPostProcessor}  {@link CommonAnnotationBeanPostProcessor}*/
		//发生一次bd的合并
		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		// 此处有点意思了，向beanFactory又add了一个BeanPostProcessorChecker，并且此事后总数设置为了getBeanPostProcessorCount和addBeanPostProcessor的总和（+1表示自己），日志记录用
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		// 此处注意：第一个参数beanPostProcessorTargetCount表示的是处理器的总数，总数（包含两个位置离的，用于后面的校验）
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		// 保存同时实现了BeanPostProcessor跟PriorityOrdered接口的后置处理器
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		// 保存实现了MergedBeanDefinitionPostProcessor接口的后置处理器
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		// 保存同时实现了BeanPostProcessor跟Ordered接口的后置处理器的名字
		List<String> orderedPostProcessorNames = new ArrayList<>();
		// 保存同时实现了BeanPostProcessor但没有实现任何排序接口的后置处理器的名字
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();

		// 遍历所有的后置处理器的名字，并根据不同类型将其放入到上面申明的不同集合中
		// 同时会将实现了PriorityOrdered接口的后置处理器创建出来
		// 如果实现了MergedBeanDefinitionPostProcessor接口，放入到internalPostProcessors
		for (String ppName : postProcessorNames) {
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
				priorityOrderedPostProcessors.add(pp);
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					internalPostProcessors.add(pp);
				}
			}
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, register the BeanPostProcessors that implement PriorityOrdered.
		//将priorityOrderedPostProcessors集合排序
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		//将priorityOrderedPostProcessors集合中的后置处理器添加到容器中
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// Next, register the BeanPostProcessors that implement Ordered.
		// 遍历所有实现了Ordered接口的后置处理器的名字，并进行创建
		// 如果实现了MergedBeanDefinitionPostProcessor接口，放入到internalPostProcessors
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String ppName : orderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			orderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		// 排序及将其添加到容器中
		sortPostProcessors(orderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// Now, register all regular BeanPostProcessors.
		// 遍历所有实现了常规后置处理器（没有实现任何排序接口）的名字，并进行创建
		// 如果实现了MergedBeanDefinitionPostProcessor接口，放入到internalPostProcessors
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String ppName : nonOrderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			nonOrderedPostProcessors.add(pp);
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		//这里需要注意下，常规后置处理器不会调用sortPostProcessors进行排序
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);

		// Finally, re-register all internal BeanPostProcessors.
		//对internalPostProcessors进行排序并添加到容器中
		sortPostProcessors(internalPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		//最后添加的这个后置处理器主要为了可以检测到所有的事件监听器
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		// Nothing to sort?
		if (postProcessors.size() <= 1) {
			return;
		}
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory) {
			comparatorToUse = ((DefaultListableBeanFactory) beanFactory).getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry) {

		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanDefinitionRegistry(registry);
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessBeanFactory(beanFactory);
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 * 把类型是BeanPostProcessor的Bean，注册到beanFactory里面去
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanPostProcessor> postProcessors) {

		if (beanFactory instanceof AbstractBeanFactory) {
			// Bulk addition is more efficient against our CopyOnWriteArrayList there
			((AbstractBeanFactory) beanFactory).addBeanPostProcessors(postProcessors);
		}
		else {
			for (BeanPostProcessor postProcessor : postProcessors) {
				beanFactory.addBeanPostProcessor(postProcessor);
			}
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			//bean不是BeanPostProcessor子类，且不是spring自身的bean
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					//创建这个Bean时容器中的后置处理器还没有完全创建完
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			//容器的beanDefinitionMap中包含该beanName对应的bd
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				//获取
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				//是否是spring自身需要创建的
				return (bd.getRole() == RootBeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

}
