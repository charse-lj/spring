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

package org.springframework.beans.factory.support;

import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.*;
import org.springframework.core.OrderComparator;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.log.LogMessage;
import org.springframework.lang.Nullable;
import org.springframework.util.*;

import javax.inject.Provider;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Spring's default implementation of the {@link ConfigurableListableBeanFactory}
 * and {@link BeanDefinitionRegistry} interfaces: a full-fledged bean factory
 * based on bean definition metadata, extensible through post-processors.
 *
 * <p>Typical usage is registering all bean definitions first (possibly read
 * from a bean definition file), before accessing beans. Bean lookup by name
 * is therefore an inexpensive operation in a local bean definition table,
 * operating on pre-resolved bean definition metadata objects.
 *
 * <p>Note that readers for specific bean definition formats are typically
 * implemented separately rather than as bean factory subclasses:
 * see for example {@link PropertiesBeanDefinitionReader} and
 * {@link org.springframework.beans.factory.xml.XmlBeanDefinitionReader}.
 *
 * <p>For an alternative implementation of the
 * {@link org.springframework.beans.factory.ListableBeanFactory} interface,
 * have a look at {@link StaticListableBeanFactory}, which manages existing
 * bean instances rather than creating new ones based on bean definitions.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Costin Leau
 * @author Chris Beams
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @see #registerBeanDefinition
 * @see #addBeanPostProcessor
 * @see #getBean
 * @see #resolveDependency
 * <p>
 * Spring内部的唯一使用的工厂实现（XmlBeanFactory已废弃）基于bean definition对象，是一个成熟的bean factroy
 * 默认实现了ListableBeanFactory和BeanDefinitionRegistry接口，基于bean definition对象，**是一个成熟的bean factroy。**它是整个bean加载的核心部分，也是spring注册加载bean的默认实现
 * DefaultListableBeanFactory既可以作为一个单独的beanFactory，也可以作为自定义beanFactory的父类
 * 至于特定格式的Bean定义信息（比如常见的有xml、注解等）的解析器可以自己实现，也可以使用原有的解析器，如： PropertiesBeanDefinitionReader和XmLBeanDefinitionReader、AnnotatedBeanDefinitionReader
 * <p>
 * extends：相当于继承了抽象类所有的实现，并且是已经具有注入功能了（含有ListableBeanFactory、ConfigurableListableBeanFactory的所有接口）
 * implements：直接实现ConfigurableListableBeanFactory，表示它具有了批量处理Bean、配置Bean等等功能
 * BeanDefinitionRegistry：该接口目前还仅有这个类实现（它父接口为：AliasRegistry	）
 * <p>
 * 为什么要拆成这么多的类和接口呢。这里面可能基于几点考虑
 * 1.功能的不同维度，分不同的接口，方便以后的维护和其他人的阅读。（代码的可读性异常的重要，特别像Spring这种持久性的项目）
 * 2.不同接口的实现，分布在不同的之类里，方便以后不同接口多种实现的扩展（单一职责，才好扩展。否则造成臃肿后，后面无法发展）
 * 3.从整个类图的分布，可以看出spring在这块是面向接口编程，后面类的实现，他们认为只是接口功能实现的一种，随时可以拓展成多种实现 （面向接口编程，能极大的解耦各模块之间的互相影响）
 * @since 16 April 2001
 */
@SuppressWarnings("serial")
public class DefaultListableBeanFactory extends AbstractAutowireCapableBeanFactory
		implements ConfigurableListableBeanFactory, BeanDefinitionRegistry, Serializable {

	@Nullable
	private static Class<?> javaxInjectProviderClass;

	static {
		try {
			javaxInjectProviderClass =
					ClassUtils.forName("javax.inject.Provider", DefaultListableBeanFactory.class.getClassLoader());
		} catch (ClassNotFoundException ex) {
			// JSR-330 API not available - Provider interface simply not supported then.
			javaxInjectProviderClass = null;
		}
	}


	/**
	 * Map from serialized id to factory instance.
	 */
	private static final Map<String, Reference<DefaultListableBeanFactory>> serializableFactories =
			new ConcurrentHashMap<>(8);

	/**
	 * Optional id for this factory, for serialization purposes.
	 */
	@Nullable
	private String serializationId;

	/**
	 * Whether to allow re-registration of a different definition with the same name.
	 */
	private boolean allowBeanDefinitionOverriding = true;

	/**
	 * Whether to allow eager class loading even for lazy-init beans.
	 */
	private boolean allowEagerClassLoading = true;

	/**
	 * Optional OrderComparator for dependency Lists and arrays.
	 */
	@Nullable
	private Comparator<Object> dependencyComparator;

	/**
	 * Resolver to use for checking if a bean definition is an autowire candidate.
	 */
	private AutowireCandidateResolver autowireCandidateResolver = SimpleAutowireCandidateResolver.INSTANCE;

	/**
	 * Map from dependency type to corresponding autowired value.
	 */
	private final Map<Class<?>, Object> resolvableDependencies = new ConcurrentHashMap<>(16);

	/**
	 * Map of bean definition objects, keyed by bean name.
	 */
	private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(256);

	/**
	 * Map from bean name to merged BeanDefinitionHolder.
	 */
	private final Map<String, BeanDefinitionHolder> mergedBeanDefinitionHolders = new ConcurrentHashMap<>(256);

	/**
	 * Map of singleton and non-singleton bean names, keyed by dependency type.
	 */
	private final Map<Class<?>, String[]> allBeanNamesByType = new ConcurrentHashMap<>(64);

	/**
	 * Map of singleton-only bean names, keyed by dependency type.
	 */
	private final Map<Class<?>, String[]> singletonBeanNamesByType = new ConcurrentHashMap<>(64);

	/**
	 * List of bean definition names, in registration order. beanDefinitionNames保存所有BeanDefinition的名字
	 */
	private volatile List<String> beanDefinitionNames = new ArrayList<>(256);

	/**
	 * List of names of manually registered singletons, in registration order. 保存了所有singleton的BeanName
	 */
	private volatile Set<String> manualSingletonNames = new LinkedHashSet<>(16);

	/**
	 * Cached array of bean definition names in case of frozen configuration.
	 */
	@Nullable
	private volatile String[] frozenBeanDefinitionNames;

	/**
	 * Whether bean definition metadata may be cached for all beans.
	 */
	private volatile boolean configurationFrozen;


	/**
	 * Create a new DefaultListableBeanFactory.
	 */
	public DefaultListableBeanFactory() {
		super();
	}

	/**
	 * Create a new DefaultListableBeanFactory with the given parent.
	 *
	 * @param parentBeanFactory the parent BeanFactory
	 */
	public DefaultListableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		// 给设置父的BeanFactory，若存在的话
		super(parentBeanFactory);
	}


	/**
	 * Specify an id for serialization purposes, allowing this BeanFactory to be
	 * deserialized from this id back into the BeanFactory object, if needed.
	 */
	public void setSerializationId(@Nullable String serializationId) {
		if (serializationId != null) {
			serializableFactories.put(serializationId, new WeakReference<>(this));
		} else if (this.serializationId != null) {
			serializableFactories.remove(this.serializationId);
		}
		this.serializationId = serializationId;
	}

	/**
	 * Return an id for serialization purposes, if specified, allowing this BeanFactory
	 * to be deserialized from this id back into the BeanFactory object, if needed.
	 *
	 * @since 4.1.2
	 */
	@Nullable
	public String getSerializationId() {
		return this.serializationId;
	}

	/**
	 * Set whether it should be allowed to override bean definitions by registering
	 * a different definition with the same name, automatically replacing the former.
	 * If not, an exception will be thrown. This also applies to overriding aliases.
	 * <p>Default is "true".
	 *
	 * @see #registerBeanDefinition
	 */
	public void setAllowBeanDefinitionOverriding(boolean allowBeanDefinitionOverriding) {
		this.allowBeanDefinitionOverriding = allowBeanDefinitionOverriding;
	}

	/**
	 * Return whether it should be allowed to override bean definitions by registering
	 * a different definition with the same name, automatically replacing the former.
	 *
	 * @since 4.1.2
	 */
	public boolean isAllowBeanDefinitionOverriding() {
		return this.allowBeanDefinitionOverriding;
	}

	/**
	 * Set whether the factory is allowed to eagerly load bean classes
	 * even for bean definitions that are marked as "lazy-init".
	 * <p>Default is "true". Turn this flag off to suppress class loading
	 * for lazy-init beans unless such a bean is explicitly requested.
	 * In particular, by-type lookups will then simply ignore bean definitions
	 * without resolved class name, instead of loading the bean classes on
	 * demand just to perform a type check.
	 *
	 * @see AbstractBeanDefinition#setLazyInit
	 */
	public void setAllowEagerClassLoading(boolean allowEagerClassLoading) {
		this.allowEagerClassLoading = allowEagerClassLoading;
	}

	/**
	 * Return whether the factory is allowed to eagerly load bean classes
	 * even for bean definitions that are marked as "lazy-init".
	 *
	 * @since 4.1.2
	 */
	public boolean isAllowEagerClassLoading() {
		return this.allowEagerClassLoading;
	}

	/**
	 * Set a {@link java.util.Comparator} for dependency Lists and arrays.
	 *
	 * @see org.springframework.core.OrderComparator
	 * @see org.springframework.core.annotation.AnnotationAwareOrderComparator
	 * @since 4.0
	 */
	public void setDependencyComparator(@Nullable Comparator<Object> dependencyComparator) {
		this.dependencyComparator = dependencyComparator;
	}

	/**
	 * Return the dependency comparator for this BeanFactory (may be {@code null}.
	 *
	 * @since 4.0
	 */
	@Nullable
	public Comparator<Object> getDependencyComparator() {
		return this.dependencyComparator;
	}

	/**
	 * Set a custom autowire candidate resolver for this BeanFactory to use
	 * when deciding whether a bean definition should be considered as a
	 * candidate for autowiring.
	 */
	public void setAutowireCandidateResolver(AutowireCandidateResolver autowireCandidateResolver) {
		Assert.notNull(autowireCandidateResolver, "AutowireCandidateResolver must not be null");
		if (autowireCandidateResolver instanceof BeanFactoryAware) {
			if (System.getSecurityManager() != null) {
				AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
					((BeanFactoryAware) autowireCandidateResolver).setBeanFactory(this);
					return null;
				}, getAccessControlContext());
			} else {
				((BeanFactoryAware) autowireCandidateResolver).setBeanFactory(this);
			}
		}
		this.autowireCandidateResolver = autowireCandidateResolver;
	}

	/**
	 * Return the autowire candidate resolver for this BeanFactory (never {@code null}).
	 */
	public AutowireCandidateResolver getAutowireCandidateResolver() {
		return this.autowireCandidateResolver;
	}


	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof DefaultListableBeanFactory) {
			DefaultListableBeanFactory otherListableFactory = (DefaultListableBeanFactory) otherFactory;
			this.allowBeanDefinitionOverriding = otherListableFactory.allowBeanDefinitionOverriding;
			this.allowEagerClassLoading = otherListableFactory.allowEagerClassLoading;
			this.dependencyComparator = otherListableFactory.dependencyComparator;
			// A clone of the AutowireCandidateResolver since it is potentially BeanFactoryAware...
			setAutowireCandidateResolver(otherListableFactory.getAutowireCandidateResolver().cloneIfNecessary());
			// Make resolvable dependencies (e.g. ResourceLoader) available here as well...
			this.resolvableDependencies.putAll(otherListableFactory.resolvableDependencies);
		}
	}


	//---------------------------------------------------------------------
	// Implementation of remaining BeanFactory methods
	//---------------------------------------------------------------------

	/**
	 * 通过类型获取bean.
	 *
	 * @param requiredType type the bean must match; can be an interface or superclass
	 * @param <T>          .
	 * @return bean.
	 * @throws BeansException .
	 */
	@Override
	public <T> T getBean(Class<T> requiredType) throws BeansException {
		return getBean(requiredType, (Object[]) null);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getBean(Class<T> requiredType, @Nullable Object... args) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		Object resolved = resolveBean(ResolvableType.forRawClass(requiredType), args, false);
		if (resolved == null) {
			throw new NoSuchBeanDefinitionException(requiredType);
		}
		return (T) resolved;
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		return getBeanProvider(ResolvableType.forRawClass(requiredType));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType) {
		return new BeanObjectProvider<T>() {
			@Override
			public T getObject() throws BeansException {
				T resolved = resolveBean(requiredType, null, false);
				if (resolved == null) {
					throw new NoSuchBeanDefinitionException(requiredType);
				}
				return resolved;
			}

			@Override
			public T getObject(Object... args) throws BeansException {
				T resolved = resolveBean(requiredType, args, false);
				if (resolved == null) {
					throw new NoSuchBeanDefinitionException(requiredType);
				}
				return resolved;
			}

			@Override
			@Nullable
			public T getIfAvailable() throws BeansException {
				try {
					return resolveBean(requiredType, null, false);
				} catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope
					return null;
				}
			}

			@Override
			public void ifAvailable(Consumer<T> dependencyConsumer) throws BeansException {
				T dependency = getIfAvailable();
				if (dependency != null) {
					try {
						dependencyConsumer.accept(dependency);
					} catch (ScopeNotActiveException ex) {
						// Ignore resolved bean in non-active scope, even on scoped proxy invocation
					}
				}
			}

			@Override
			@Nullable
			public T getIfUnique() throws BeansException {
				try {
					return resolveBean(requiredType, null, true);
				} catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope
					return null;
				}
			}

			@Override
			public void ifUnique(Consumer<T> dependencyConsumer) throws BeansException {
				T dependency = getIfUnique();
				if (dependency != null) {
					try {
						dependencyConsumer.accept(dependency);
					} catch (ScopeNotActiveException ex) {
						// Ignore resolved bean in non-active scope, even on scoped proxy invocation
					}
				}
			}

			@Override
			public Stream<T> stream() {
				return Arrays.stream(getBeanNamesForTypedStream(requiredType))
						.map(name -> (T) getBean(name))
						.filter(bean -> !(bean instanceof NullBean));
			}

			@Override
			public Stream<T> orderedStream() {
				String[] beanNames = getBeanNamesForTypedStream(requiredType);
				if (beanNames.length == 0) {
					return Stream.empty();
				}
				Map<String, T> matchingBeans = new LinkedHashMap<>(beanNames.length);
				for (String beanName : beanNames) {
					Object beanInstance = getBean(beanName);
					if (!(beanInstance instanceof NullBean)) {
						matchingBeans.put(beanName, (T) beanInstance);
					}
				}
				Stream<T> stream = matchingBeans.values().stream();
				return stream.sorted(adaptOrderComparator(matchingBeans));
			}
		};
	}

	/**
	 * @param requiredType    需要的类型.
	 * @param args            需要的参数对象.
	 * @param nonUniqueAsNull 如果不是单个，返回null?
	 * @param <T>
	 * @return
	 */
	@Nullable
	private <T> T resolveBean(ResolvableType requiredType, @Nullable Object[] args, boolean nonUniqueAsNull) {
		NamedBeanHolder<T> namedBean = resolveNamedBean(requiredType, args, nonUniqueAsNull);
		if (namedBean != null) {
			return namedBean.getBeanInstance();
		}
		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof DefaultListableBeanFactory) {
			return ((DefaultListableBeanFactory) parent).resolveBean(requiredType, args, nonUniqueAsNull);
		} else if (parent != null) {
			ObjectProvider<T> parentProvider = parent.getBeanProvider(requiredType);
			if (args != null) {
				return parentProvider.getObject(args);
			} else {
				return (nonUniqueAsNull ? parentProvider.getIfUnique() : parentProvider.getIfAvailable());
			}
		}
		return null;
	}

	private String[] getBeanNamesForTypedStream(ResolvableType requiredType) {
		return BeanFactoryUtils.beanNamesForTypeIncludingAncestors(this, requiredType);
	}


	//---------------------------------------------------------------------
	// Implementation of ListableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public boolean containsBeanDefinition(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		return this.beanDefinitionMap.containsKey(beanName);
	}

	@Override
	public int getBeanDefinitionCount() {
		return this.beanDefinitionMap.size();
	}

	@Override
	public String[] getBeanDefinitionNames() {
		String[] frozenNames = this.frozenBeanDefinitionNames;
		if (frozenNames != null) {
			return frozenNames.clone();
		} else {
			return StringUtils.toStringArray(this.beanDefinitionNames);
		}
	}

	/**
	 * 只会读取 BeanDefinition 信息或部分已经实例化 FactoryBean 来获取 Bean 的类型，该过程中不会实例化 Bean
	 *
	 * @param type the generically typed class or interface to match .
	 * @return
	 */
	@Override
	public String[] getBeanNamesForType(ResolvableType type) {
		return getBeanNamesForType(type, true, true);
	}

	/**
	 * @param type                 the generically typed class or interface to match
	 * @param includeNonSingletons whether to include prototype or scoped beans too
	 *                             or just singletons (also applies to FactoryBeans)
	 * @param allowEagerInit       whether to initialize <i>lazy-init singletons</i> and
	 *                             <i>objects created by FactoryBeans</i> (or by factory methods with a
	 *                             "factory-bean" reference) for the type check. Note that FactoryBeans need to be
	 *                             eagerly initialized to determine their type: So be aware that passing in "true"
	 *                             for this flag will initialize FactoryBeans and "factory-bean" references. 表示是否提前部分初始化 FactoryBean
	 * @return
	 */
	@Override
	public String[] getBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		Class<?> resolved = type.resolve();
		//没有泛型
		if (resolved != null && !type.hasGenerics()) {
			return getBeanNamesForType(resolved, includeNonSingletons, allowEagerInit);
		} else {
			return doGetBeanNamesForType(type, includeNonSingletons, allowEagerInit);
		}
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type) {
		return getBeanNamesForType(type, true, true);
	}

	/**
	 * @param type                 the class or interface to match, or {@code null} for all bean names
	 * @param includeNonSingletons whether to include prototype or scoped beans too
	 *                             or just singletons (also applies to FactoryBeans)
	 *                             表示是否包含非单例 Bean
	 * @param allowEagerInit       whether to initialize <i>lazy-init singletons</i> and
	 *                             <i>objects created by FactoryBeans</i> (or by factory methods with a
	 *                             "factory-bean" reference) for the type check. Note that FactoryBeans need to be
	 *                             eagerly initialized to determine their type: So be aware that passing in "true"
	 *                             for this flag will initialize FactoryBeans and "factory-bean" references.
	 *                             表示是否提前部分初始化 FactoryBean(为什么说是部分初始化？是因为只实例化 FactoryBean，而不进行属性注入）
	 * @return 发生bd的合并
	 */
	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
		/*
		 * 1. 查询的结果不使用缓存?是因为此时查询的结果可能不正确,原因很简单，因为该方法不会通过提前实例化 Bean 的方式获取其类型，只会根据 BeanDefinition 或 FactoryBean#getObjectType 获取其类型。如果不实例化对象，有些场景可能并不能获取对象的类型
		 * 使用缓存的场景如下
		 * 1.1 configurationFrozen表示是否冻结BeanDefinition，不允许修改，因此为false时,查询的结果可能有误
		     一旦调用AbstractApplicationContext#finishBeanFactoryInitialization(beanFactory)则configurationFrozen=true;容器启动过程中会走if语句
		   1.2 type=null，查找所有Bean?
		 * 1.3 !allowEagerInit 表示不允许提前初始化FactoryBean，那就只能通过 FactoryBean 上的泛型来获取其类型了，也可能导致查询的结果不准确
		*/
		if (!isConfigurationFrozen() || type == null || !allowEagerInit) {
			return doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, allowEagerInit);
		}
		// 2. 允许使用缓存，此时容器已经启动完成，bean已经加载，BeanDefinition不允许修改
		Map<Class<?>, String[]> cache =
				(includeNonSingletons ? this.allBeanNamesByType : this.singletonBeanNamesByType);
		String[] resolvedBeanNames = cache.get(type);
		if (resolvedBeanNames != null) {
			return resolvedBeanNames;
		}
		resolvedBeanNames = doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, true);
		if (ClassUtils.isCacheSafe(type, getBeanClassLoader())) {
			cache.put(type, resolvedBeanNames);
		}
		return resolvedBeanNames;
	}

	/**
	 * 方法整体逻辑十分清晰，先查找 Spring 中注册的 BeanDefinition，再查找 Spring 托管的 Bean（即通过 registerSingleton 方法注册）。每种情况都先匹配 Bean，再匹配 FactoryBean
	 * 注册的 BeanDefinition：通过 registerBeanDefinition 方法将 BeanDefinition 注册到容器中，大多数情况
	 * isTypeMatch(beanName, type)：匹配 Bean，如果是 FactoryBean 会部分初始化来获取其类型。
	 * isTypeMatch(&beanName, type)：匹配 FactoryBean。
	 * 托管的 Bean（即通过 registerSingleton 方法注册）：通过 registerSingleton 方法直接注册到 manualSingletonNames 集合中，极少数
	 * isTypeMatch(beanName, type)
	 * isTypeMatch(&beanName, type)
	 *
	 * @param type                 需要的bean的类型 .
	 * @param includeNonSingletons only singleton or not.
	 * @param allowEagerInit       是否实例化懒加载的单例或者通过工厂方法(静态或者实例)创建的对象
	 * @return .
	 */
	private String[] doGetBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		List<String> result = new ArrayList<>();

		// Check all bean definitions.
		for (String beanName : this.beanDefinitionNames) {
			// Only consider bean as eligible if the bean name is not defined as alias for some other bean.
			//beanName不能是别名.
			if (!isAlias(beanName)) {
				try {
					RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
					// Only check bean definition if it is complete.
					// 1.1 校验 BeanDefinition

					/*Spring 如何判断能否从 BeanDefinition 获取其类型?
					 *-->1:不能是抽象类，这点显而易见
					 *-->2:allowEagerInit=true 表示可以提前初始化 FactoryBean。因为 FactoryBean 只有初始化才能通过 getObjectType 获取其类型。当然还有一种兜底的方案是 FactoryBean 上的泛型
					 *-->3:判断 JVM 是否允许提前加载 mdb.beanClass，因为获取 Bean 类型时会将该类加载到 JVM 中。默认情况下永远为 true。
					 *    mdb.beanClass 已经解析完成，这毫无疑问可以获取 bean 类型。
					 *    mbd.lazyInit=false 非懒加载 bean 时解析 Bean 类型，因为此时需要在 JVM 上加载 mdb.className 为 mdb.beanClass。默认为 false，也就是可以加载 Bean 类型。
					 *    allowEagerClassLoading=true 表示强制加载 mdb.className，即使 mbd.lazyInit=true。Spring 容器 allowEagerClassLoading 默认为 true，而且目前为至，没有发现 Spring 的上下文将其修改为 false 的。也就是这一项条件判断永远为 true。
					 *-->4：requiresEagerInitForType()通常情况下返回 false，只有当工厂类为 FactoryBean 时且未初始化时才返回 true 时。如果为 true 则表示 FactoryBean 需要提前初始化，即必须允许提前初始化，也就是说 allowEagerInit=true
					 *
					 */
					if (!mbd.isAbstract() && (allowEagerInit ||
							(mbd.hasBeanClass() || !mbd.isLazyInit() || isAllowEagerClassLoading()) &&
									!requiresEagerInitForType(mbd.getFactoryBeanName()))) {
						boolean isFactoryBean = isFactoryBean(beanName, mbd);
						BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
						// 1.2 匹配beanName

						//首先要对 isTypeMatch 方法有一定的了解，isTypeMatch 获取 Bean 类型时，如果发现是 FactoryBean，会提前部分初始化 FactoryBean，所以判断前需要判断能不能调用该方法
						boolean matchFound = false;
						//allowEagerInit=true 允许提前部分初始化
						boolean allowFactoryBeanInit = (allowEagerInit || containsSingleton(beanName));
						boolean isNonLazyDecorated = (dbd != null && !mbd.isLazyInit());
						if (!isFactoryBean) {
							//不是factoryBean
							//允许非单例或者beanName是单例
							if (includeNonSingletons || isSingleton(beanName, mbd, dbd)) {
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
						} else {
							//factoryBean
							if (includeNonSingletons || isNonLazyDecorated ||
									(allowFactoryBeanInit && isSingleton(beanName, mbd, dbd))) {
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
							if (!matchFound) {
								// In case of FactoryBean, try to match FactoryBean instance itself next.
								beanName = FACTORY_BEAN_PREFIX + beanName;
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
						}
						if (matchFound) {
							result.add(beanName);
						}
					}
				} catch (CannotLoadBeanClassException | BeanDefinitionStoreException ex) {
					if (allowEagerInit) {
						throw ex;
					}
					// Probably a placeholder: let's ignore it for type matching purposes.
					LogMessage message = (ex instanceof CannotLoadBeanClassException ?
							LogMessage.format("Ignoring bean class loading failure for bean '%s'", beanName) :
							LogMessage.format("Ignoring unresolvable metadata in bean definition '%s'", beanName));
					logger.trace(message, ex);
					// Register exception, in case the bean was accidentally unresolvable.
					onSuppressedException(ex);
				} catch (NoSuchBeanDefinitionException ex) {
					// Bean definition got removed while we were iterating -> ignore.
				}
			}
		}

		// Check manually registered singletons too.
		for (String beanName : this.manualSingletonNames) {
			try {
				// In case of FactoryBean, match object created by FactoryBean.
				if (isFactoryBean(beanName)) {
					// 2.1 匹配beanName
					if ((includeNonSingletons || isSingleton(beanName)) && isTypeMatch(beanName, type)) {
						result.add(beanName);
						// Match found for this bean: do not match FactoryBean itself anymore.
						continue;
					}
					// 2.2 没有匹配成功，继续匹配&beanName
					// In case of FactoryBean, try to match FactoryBean itself next.
					beanName = FACTORY_BEAN_PREFIX + beanName;
				}
				// Match raw bean instance (might be raw FactoryBean).
				if (isTypeMatch(beanName, type)) {
					result.add(beanName);
				}
			} catch (NoSuchBeanDefinitionException ex) {
				// Shouldn't happen - probably a result of circular reference resolution...
				logger.trace(LogMessage.format(
						"Failed to check manually registered singleton with name '%s'", beanName), ex);
			}
		}

		return StringUtils.toStringArray(result);
	}

	private boolean isSingleton(String beanName, RootBeanDefinition mbd, @Nullable BeanDefinitionHolder dbd) {
		return (dbd != null ? mbd.isSingleton() : isSingleton(beanName));
	}

	/**
	 * Check whether the specified bean would need to be eagerly initialized
	 * in order to determine its type.
	 *
	 * @param factoryBeanName a factory-bean reference that the bean definition
	 *                        defines a factory method for
	 * @return whether eager initialization is necessary
	 * ① mbd 存在 factoryBeanName 工厂类
	 * ② factoryBeanName 为 FactoryBean
	 * ③ FactoryBean 未实例化。
	 */
	private boolean requiresEagerInitForType(@Nullable String factoryBeanName) {
		return (factoryBeanName != null && isFactoryBean(factoryBeanName) && !containsSingleton(factoryBeanName));
	}

	/**
	 * 获取集合 Bean 类型实例
	 *
	 * @param type the class or interface to match, or {@code null} for all concrete beans
	 * @param <T>
	 * @return
	 * @throws BeansException
	 */
	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type) throws BeansException {
		return getBeansOfType(type, true, true);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> Map<String, T> getBeansOfType(
			@Nullable Class<T> type, boolean includeNonSingletons, boolean allowEagerInit) throws BeansException {

		//会初始化 Bean，根据其 BeanDefinition 或 FactoryBean#getObjectType 获取其类型
		String[] beanNames = getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
		Map<String, T> result = new LinkedHashMap<>(beanNames.length);
		for (String beanName : beanNames) {
			try {
				Object beanInstance = getBean(beanName);
				if (!(beanInstance instanceof NullBean)) {
					result.put(beanName, (T) beanInstance);
				}
			} catch (BeanCreationException ex) {
				Throwable rootCause = ex.getMostSpecificCause();
				if (rootCause instanceof BeanCurrentlyInCreationException) {
					BeanCreationException bce = (BeanCreationException) rootCause;
					String exBeanName = bce.getBeanName();
					if (exBeanName != null && isCurrentlyInCreation(exBeanName)) {
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring match to currently created bean '" + exBeanName + "': " +
									ex.getMessage());
						}
						onSuppressedException(ex);
						// Ignore: indicates a circular reference when autowiring constructors.
						// We want to find matches other than the currently created bean itself.
						continue;
					}
				}
				throw ex;
			}
		}
		return result;
	}

	/**
	 * 注解查找bean名称列表
	 *
	 * @param annotationType the type of annotation to look for
	 *                       (at class, interface or factory method level of the specified bean)
	 * @return
	 */
	@Override
	public String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType) {
		List<String> result = new ArrayList<>();
		for (String beanName : this.beanDefinitionNames) {
			BeanDefinition bd = this.beanDefinitionMap.get(beanName);
			if (bd != null && !bd.isAbstract() && findAnnotationOnBean(beanName, annotationType) != null) {
				result.add(beanName);
			}
		}
		for (String beanName : this.manualSingletonNames) {
			if (!result.contains(beanName) && findAnnotationOnBean(beanName, annotationType) != null) {
				result.add(beanName);
			}
		}
		return StringUtils.toStringArray(result);
	}

	@Override
	public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
		String[] beanNames = getBeanNamesForAnnotation(annotationType);
		Map<String, Object> result = new LinkedHashMap<>(beanNames.length);
		for (String beanName : beanNames) {
			Object beanInstance = getBean(beanName);
			if (!(beanInstance instanceof NullBean)) {
				result.put(beanName, beanInstance);
			}
		}
		return result;
	}

	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType)
			throws NoSuchBeanDefinitionException {

		return findMergedAnnotationOnBean(beanName, annotationType)
				.synthesize(MergedAnnotation::isPresent).orElse(null);
	}

	/**
	 * 为什么要先调用 getType 方法查找，查找不到还要通过 BeanDefinition 查找，按理说，getType 就是通过 BeanDefinition 获取其类型的？
	 * 问题的关键在于 getType 先按实例进行类型自省，再到 BeanDefinition 进行类型自省，对象类型可以不能正确获取。如果是 JDK 动态代理对象，则不能正确获取类型
	 *
	 * @param beanName
	 * @param annotationType
	 * @param <A>
	 * @return
	 */
	private <A extends Annotation> MergedAnnotation<A> findMergedAnnotationOnBean(
			String beanName, Class<A> annotationType) {

		// 1. 获取beanName类型，可以是代理后的实例类型，导致无法获取注解
		Class<?> beanType = getType(beanName);
		if (beanType != null) {
			MergedAnnotation<A> annotation =
					MergedAnnotations.from(beanType, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
			if (annotation.isPresent()) {
				return annotation;
			}
		}
		// 2. BeanDefinition中beanName类型
		if (containsBeanDefinition(beanName)) {
			RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
			// Check raw bean class, e.g. in case of a proxy.
			if (bd.hasBeanClass()) {
				Class<?> beanClass = bd.getBeanClass();
				if (beanClass != beanType) {
					MergedAnnotation<A> annotation =
							MergedAnnotations.from(beanClass, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
					if (annotation.isPresent()) {
						return annotation;
					}
				}
			}
			// Check annotations declared on factory method, if any.
			Method factoryMethod = bd.getResolvedFactoryMethod();
			if (factoryMethod != null) {
				MergedAnnotation<A> annotation =
						MergedAnnotations.from(factoryMethod, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
				if (annotation.isPresent()) {
					return annotation;
				}
			}
		}
		return MergedAnnotation.missing();
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableListableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public void registerResolvableDependency(Class<?> dependencyType, @Nullable Object autowiredValue) {
		Assert.notNull(dependencyType, "Dependency type must not be null");
		if (autowiredValue != null) {
			if (!(autowiredValue instanceof ObjectFactory || dependencyType.isInstance(autowiredValue))) {
				throw new IllegalArgumentException("Value [" + autowiredValue +
						"] does not implement specified dependency type [" + dependencyType.getName() + "]");
			}
			this.resolvableDependencies.put(dependencyType, autowiredValue);
		}
	}

	@Override
	public boolean isAutowireCandidate(String beanName, DependencyDescriptor descriptor)
			throws NoSuchBeanDefinitionException {

		return isAutowireCandidate(beanName, descriptor, getAutowireCandidateResolver());
	}

	/**
	 * Determine whether the specified bean definition qualifies as an autowire candidate,
	 * to be injected into other beans which declare a dependency of matching type.
	 *
	 * @param beanName   the name of the bean definition to check
	 * @param descriptor the descriptor of the dependency to resolve
	 * @param resolver   the AutowireCandidateResolver to use for the actual resolution algorithm
	 * @return whether the bean should be considered as autowire candidate
	 */
	protected boolean isAutowireCandidate(
			String beanName, DependencyDescriptor descriptor, AutowireCandidateResolver resolver)
			throws NoSuchBeanDefinitionException {

		String bdName = BeanFactoryUtils.transformedBeanName(beanName);
		if (containsBeanDefinition(bdName)) {
			// 若存在Bean定义，就走这里（因为有的Bean可能是直接registerSingleton进来的，是不存在Bean定义的）
			// 我们的注入，绝大部分情况都走这里
			//getMergedLocalBeanDefinition方法的作用就是获取缓存的BeanDefinition对象并合并其父类和本身的属性
			return isAutowireCandidate(beanName, getMergedLocalBeanDefinition(bdName), descriptor, resolver);
		} else if (containsSingleton(beanName)) {
			// 若已经存在实例了，就走这里
			return isAutowireCandidate(beanName, new RootBeanDefinition(getType(beanName)), descriptor, resolver);
		}

		// 父容器  有可能为null，为null就肯定走else默认值了 true 可以注入
		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof DefaultListableBeanFactory) {
			// No bean definition found in this factory -> delegate to parent.
			return ((DefaultListableBeanFactory) parent).isAutowireCandidate(beanName, descriptor, resolver);
		} else if (parent instanceof ConfigurableListableBeanFactory) {
			// If no DefaultListableBeanFactory, can't pass the resolver along.
			return ((ConfigurableListableBeanFactory) parent).isAutowireCandidate(beanName, descriptor);
		} else {
			// 默认值是true
			return true;
		}
	}

	/**
	 * Determine whether the specified bean definition qualifies as an autowire candidate,
	 * to be injected into other beans which declare a dependency of matching type.
	 *
	 * @param beanName   the name of the bean definition to check
	 * @param mbd        the merged bean definition to check
	 * @param descriptor the descriptor of the dependency to resolve
	 * @param resolver   the AutowireCandidateResolver to use for the actual resolution algorithm
	 * @return whether the bean should be considered as autowire candidate
	 */
	protected boolean isAutowireCandidate(String beanName, RootBeanDefinition mbd,
										  DependencyDescriptor descriptor, AutowireCandidateResolver resolver) {

		String bdName = BeanFactoryUtils.transformedBeanName(beanName);

		//resolveBeanClass 这个方法之前提到过，主要是保证此Class已经被加载进来了
		resolveBeanClass(mbd, bdName);

		//是否已经指定引用非重载方法的工厂方法名。  默认值是true
		if (mbd.isFactoryMethodUnique && mbd.factoryMethodToIntrospect == null) {
			new ConstructorResolver(this).resolveFactoryMethodIfPossible(mbd);
		}
		BeanDefinitionHolder holder = (beanName.equals(bdName) ?
				this.mergedBeanDefinitionHolders.computeIfAbsent(beanName,
						key -> new BeanDefinitionHolder(mbd, beanName, getAliases(bdName))) :
				new BeanDefinitionHolder(mbd, beanName, getAliases(bdName)));
		// 核心来了。ContextAnnotationAutowireCandidateResolver#isAutowireCandidate方法
		// 真正的实现在父类：QualifierAnnotationAutowireCandidateResolver它身上
		return resolver.isAutowireCandidate(holder, descriptor);
	}

	@Override
	public BeanDefinition getBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		//这一步说白了：就是获取前面已经保存在IOC容器里的BeanDefinition定义信息
		BeanDefinition bd = this.beanDefinitionMap.get(beanName);
		if (bd == null) {
			if (logger.isTraceEnabled()) {
				logger.trace("No bean named '" + beanName + "' found in " + this);
			}
			throw new NoSuchBeanDefinitionException(beanName);
		}
		return bd;
	}

	@Override
	public Iterator<String> getBeanNamesIterator() {
		CompositeIterator<String> iterator = new CompositeIterator<>();
		iterator.add(this.beanDefinitionNames.iterator());
		iterator.add(this.manualSingletonNames.iterator());
		return iterator;
	}

	@Override
	protected void clearMergedBeanDefinition(String beanName) {
		super.clearMergedBeanDefinition(beanName);
		//beanFactory的mergedBeanDefinitionHolders中移除beanName对应的值
		this.mergedBeanDefinitionHolders.remove(beanName);
	}

	@Override
	public void clearMetadataCache() {
		super.clearMetadataCache();
		this.mergedBeanDefinitionHolders.clear();
		clearByTypeCache();
	}

	@Override
	public void freezeConfiguration() {
		this.configurationFrozen = true;
		this.frozenBeanDefinitionNames = StringUtils.toStringArray(this.beanDefinitionNames);
	}

	@Override
	public boolean isConfigurationFrozen() {
		return this.configurationFrozen;
	}

	/**
	 * Considers all beans as eligible for metadata caching
	 * if the factory's configuration has been marked as frozen.
	 *
	 * @see #freezeConfiguration()
	 */
	@Override
	protected boolean isBeanEligibleForMetadataCaching(String beanName) {
		return (this.configurationFrozen || super.isBeanEligibleForMetadataCaching(beanName));
	}

	/**
	 * 此处必须说明：此处绝大部分的单例Bean定义信息都会被实例化，但是如果是通过FactoryBean定义的，它是懒加载的（如果没人使用，就先不会实例化。只会到使用的时候才实例化~）.
	 *
	 * @throws BeansException
	 */
	@Override
	public void preInstantiateSingletons() throws BeansException {
		if (logger.isTraceEnabled()) {
			logger.trace("Pre-instantiating singletons in " + this);
		}

		// Iterate over a copy to allow for init methods which in turn register new bean definitions.
		// While this may not be part of the regular factory bootstrap, it does otherwise work fine.
		// 此处目的，把所有的bean定义信息名称，赋值到一个新的集合中
		List<String> beanNames = new ArrayList<>(this.beanDefinitionNames);

		// Trigger initialization of all non-lazy singleton beans...
		// 不是抽象类&&是单例&&不是懒加载
		for (String beanName : beanNames) {
			//Spring在实例化一个对象也会进行bd的合并。
			RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
			if (!bd.isAbstract() && bd.isSingleton() && !bd.isLazyInit()) {
				// 这是Spring提供的对FacotyBean模式的支持：比如第三方框架的继承经常采用这种方式
				// 如果是工厂Bean，那就会此工厂Bean放进去
				if (isFactoryBean(beanName)) {
					// 拿到工厂Bean本身，注意有前缀为'&'
					Object bean = getBean(FACTORY_BEAN_PREFIX + beanName);
					if (bean instanceof FactoryBean) {
						//是一个FactoryBean
						FactoryBean<?> factory = (FactoryBean<?>) bean;
						boolean isEagerInit;
						//做权限校验，判断是否是一个SmartFactoryBean，并且不是懒加载的
						if (System.getSecurityManager() != null && factory instanceof SmartFactoryBean) {
							isEagerInit = AccessController.doPrivileged(
									(PrivilegedAction<Boolean>) ((SmartFactoryBean<?>) factory)::isEagerInit,
									getAccessControlContext());
						} else {
							//判断是否是一个SmartFactoryBean，并且不是懒加载
							isEagerInit = (factory instanceof SmartFactoryBean &&
									((SmartFactoryBean<?>) factory).isEagerInit());
						}
						// true：表示渴望马上被初始化的，那就拿上执行初始化~
						if (isEagerInit) {
							getBean(beanName);
						}
					}
				} else {
					// 这里，就是普通单例Bean正式初始化了~  核心逻辑在方法：doGetBean
					// 关于doGetBean方法的详解：下面有贴出博文，专文讲解
					getBean(beanName);
				}
			}
		}

		// Trigger post-initialization callback for all applicable beans...
		// SmartInitializingSingleton：所有非lazy单例Bean实例化完成后的回调方法 Spring4.1才提供
		// SmartInitializingSingleton的afterSingletonsInstantiated方法是在所有单例bean都已经被创建后执行的
		// InitializingBean#afterPropertiesSet 是在仅仅自己被创建好了执行的
		// 比如EventListenerMethodProcessor它在afterSingletonsInstantiated方法里就去处理所有的Bean的方法
		// 看看哪些被标注了@EventListener注解，提取处理也作为一个Listener放到容器addApplicationListener里面去
		for (String beanName : beanNames) {
			Object singletonInstance = getSingleton(beanName);
			if (singletonInstance instanceof SmartInitializingSingleton) {
				SmartInitializingSingleton smartSingleton = (SmartInitializingSingleton) singletonInstance;
				if (System.getSecurityManager() != null) {
					AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
						smartSingleton.afterSingletonsInstantiated();
						return null;
					}, getAccessControlContext());
				} else {
					// 比如：ScheduledAnnotationBeanPostProcessor CacheAspectSupport  MBeanExporter等等
					smartSingleton.afterSingletonsInstantiated();
				}
			}
		}
	}


	//---------------------------------------------------------------------
	// Implementation of BeanDefinitionRegistry interface
	// beanName放入到beanDefinitionNames
	// beanDefinitionMap -->{beanName:beanDefinition}
	//---------------------------------------------------------------------

	@Override
	public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition)
			throws BeanDefinitionStoreException {

		Assert.hasText(beanName, "Bean name must not be empty");
		Assert.notNull(beanDefinition, "BeanDefinition must not be null");

		if (beanDefinition instanceof AbstractBeanDefinition) {
			try {
				((AbstractBeanDefinition) beanDefinition).validate();
			} catch (BeanDefinitionValidationException ex) {
				throw new BeanDefinitionStoreException(beanDefinition.getResourceDescription(), beanName,
						"Validation of bean definition failed", ex);
			}
		}

		//beanDefinitionMap --> {beanName:beanDefinition}
		// 因为 beanDefinitionMap 是全局变量，这里定会存在并发访问的情况
		BeanDefinition existingDefinition = this.beanDefinitionMap.get(beanName);
		// 处理注册已经注册的 beanName 情况
		if (existingDefinition != null) {
			//beanFactory是否允许 allowBeanDefinitionOverriding,如果对应的 beanName 已经注册且在配置中配置了 bean 不允许覆盖，则抛出异常
			if (!isAllowBeanDefinitionOverriding()) {
				throw new BeanDefinitionOverrideException(beanName, beanDefinition, existingDefinition);
			}
			//已存在的BeanDefinition 的作用域与想要添加的作用域比较
			else if (existingDefinition.getRole() < beanDefinition.getRole()) {
				// e.g. was ROLE_APPLICATION, now overriding with ROLE_SUPPORT or ROLE_INFRASTRUCTURE
				if (logger.isInfoEnabled()) {
					logger.info("Overriding user-defined bean definition for bean '" + beanName +
							"' with a framework-generated bean definition: replacing [" +
							existingDefinition + "] with [" + beanDefinition + "]");
				}
			}
			//已经存在的和将添加的不相等
			else if (!beanDefinition.equals(existingDefinition)) {
				//记录
				if (logger.isDebugEnabled()) {
					logger.debug("Overriding bean definition for bean '" + beanName +
							"' with a different definition: replacing [" + existingDefinition +
							"] with [" + beanDefinition + "]");
				}
			} else {
				if (logger.isTraceEnabled()) {
					logger.trace("Overriding bean definition for bean '" + beanName +
							"' with an equivalent definition: replacing [" + existingDefinition +
							"] with [" + beanDefinition + "]");
				}
			}
			//存
			this.beanDefinitionMap.put(beanName, beanDefinition);
		} else {
			// 程序已经启动，不能再修改集合元素(用于稳定迭代)
			if (hasBeanCreationStarted()) {
				// Cannot modify startup-time collection elements anymore (for stable iteration)
				synchronized (this.beanDefinitionMap) {
					//存
					this.beanDefinitionMap.put(beanName, beanDefinition);
					//copy
					List<String> updatedDefinitions = new ArrayList<>(this.beanDefinitionNames.size() + 1);
					updatedDefinitions.addAll(this.beanDefinitionNames);
					updatedDefinitions.add(beanName);
					//copy and set beanFactory的beanDefinitionNames
					this.beanDefinitionNames = updatedDefinitions;
					removeManualSingletonName(beanName);
				}
			} else {
				// 仍处于启动注册阶段
				// 注册 beanDefinition，并记录 beanName
				// Still in startup registration phase
				this.beanDefinitionMap.put(beanName, beanDefinition);
				this.beanDefinitionNames.add(beanName);
				removeManualSingletonName(beanName);
			}
			this.frozenBeanDefinitionNames = null;
		}

		//beanName对应的BeanDefinition在beanFacoty中已经存在 或者在beanFactory中的singletonObjects 中存在
		if (existingDefinition != null || containsSingleton(beanName)) {
			//重置beanName对应的BeanDefinition
			resetBeanDefinition(beanName);
		} else if (isConfigurationFrozen()) {
			clearByTypeCache();
		}
	}

	@Override
	public void removeBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		Assert.hasText(beanName, "'beanName' must not be empty");

		BeanDefinition bd = this.beanDefinitionMap.remove(beanName);
		if (bd == null) {
			if (logger.isTraceEnabled()) {
				logger.trace("No bean named '" + beanName + "' found in " + this);
			}
			throw new NoSuchBeanDefinitionException(beanName);
		}

		if (hasBeanCreationStarted()) {
			// Cannot modify startup-time collection elements anymore (for stable iteration)
			synchronized (this.beanDefinitionMap) {
				List<String> updatedDefinitions = new ArrayList<>(this.beanDefinitionNames);
				updatedDefinitions.remove(beanName);
				this.beanDefinitionNames = updatedDefinitions;
			}
		} else {
			// Still in startup registration phase
			this.beanDefinitionNames.remove(beanName);
		}
		this.frozenBeanDefinitionNames = null;

		resetBeanDefinition(beanName);
	}

	/**
	 * Reset all bean definition caches for the given bean,
	 * including the caches of beans that are derived from it.
	 * <p>Called after an existing bean definition has been replaced or removed,
	 * triggering {@link #clearMergedBeanDefinition}, {@link #destroySingleton}
	 * and {@link MergedBeanDefinitionPostProcessor#resetBeanDefinition} on the
	 * given bean and on all bean definitions that have the given bean as parent.
	 *
	 * @param beanName the name of the bean to reset
	 * @see #registerBeanDefinition
	 * @see #removeBeanDefinition
	 */
	protected void resetBeanDefinition(String beanName) {
		// Remove the merged bean definition for the given bean, if already created.
		clearMergedBeanDefinition(beanName);

		// Remove corresponding bean from singleton cache, if any. Shouldn't usually
		// be necessary, rather just meant for overriding a context's default beans
		// (e.g. the default StaticMessageSource in a StaticApplicationContext).
		//销毁beanName对应的bean
		destroySingleton(beanName);

		// Notify all post-processors that the specified bean definition has been reset.
		for (MergedBeanDefinitionPostProcessor processor : getBeanPostProcessorCache().mergedDefinition) {
			processor.resetBeanDefinition(beanName);
		}

		// Reset all bean definitions that have the given bean as parent (recursively).
		for (String bdName : this.beanDefinitionNames) {
			if (!beanName.equals(bdName)) {
				BeanDefinition bd = this.beanDefinitionMap.get(bdName);
				// Ensure bd is non-null due to potential concurrent modification of beanDefinitionMap.
				if (bd != null && beanName.equals(bd.getParentName())) {
					resetBeanDefinition(bdName);
				}
			}
		}
	}

	/**
	 * Only allows alias overriding if bean definition overriding is allowed.
	 */
	@Override
	protected boolean allowAliasOverriding() {
		return isAllowBeanDefinitionOverriding();
	}

	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		super.registerSingleton(beanName, singletonObject);
		updateManualSingletonNames(set -> set.add(beanName), set -> !this.beanDefinitionMap.containsKey(beanName));
		clearByTypeCache();
	}

	@Override
	public void destroySingletons() {
		super.destroySingletons();
		updateManualSingletonNames(Set::clear, set -> !set.isEmpty());
		clearByTypeCache();
	}

	@Override
	public void destroySingleton(String beanName) {
		super.destroySingleton(beanName);
		removeManualSingletonName(beanName);
		clearByTypeCache();
	}

	private void removeManualSingletonName(String beanName) {
		updateManualSingletonNames(set -> set.remove(beanName), set -> set.contains(beanName));
	}

	/**
	 * Update the factory's internal set of manual singleton names.
	 *
	 * @param action    the modification action  #set -> set.add(beanName)#
	 * @param condition a precondition for the modification action  #set -> !this.beanDefinitionMap.containsKey(beanName)#
	 *                  (if this condition does not apply, the action can be skipped)
	 */
	private void updateManualSingletonNames(Consumer<Set<String>> action, Predicate<Set<String>> condition) {
		//beanFactory的Set<String> alreadyCreated 是否为空
		if (hasBeanCreationStarted()) {
			//不为空
			// Cannot modify startup-time collection elements anymore (for stable iteration)
			synchronized (this.beanDefinitionMap) {
				// beanFactory 的Set<String> manualSingletonNames 中存在 beanName
				if (condition.test(this.manualSingletonNames)) {
					//copy manualSingletonNames
					Set<String> updatedSingletons = new LinkedHashSet<>(this.manualSingletonNames);
					//移除
					action.accept(updatedSingletons);
					//设值
					this.manualSingletonNames = updatedSingletons;
				}
			}
		} else {
			// Still in startup registration phase
			if (condition.test(this.manualSingletonNames)) {
				action.accept(this.manualSingletonNames);
			}
		}
	}

	/**
	 * Remove any assumptions about by-type mappings.
	 */
	private void clearByTypeCache() {
		this.allBeanNamesByType.clear();
		this.singletonBeanNamesByType.clear();
	}


	//---------------------------------------------------------------------
	// Dependency resolution functionality
	//---------------------------------------------------------------------

	/**
	 * 获取单个 Bean 类型实例
	 *
	 * @param requiredType type the bean must match; can be an interface or superclass
	 * @param <T>
	 * @return
	 * @throws BeansException
	 */
	@Override
	public <T> NamedBeanHolder<T> resolveNamedBean(Class<T> requiredType) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		NamedBeanHolder<T> namedBean = resolveNamedBean(ResolvableType.forRawClass(requiredType), null, false);
		if (namedBean != null) {
			return namedBean;
		}
		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof AutowireCapableBeanFactory) {
			return ((AutowireCapableBeanFactory) parent).resolveNamedBean(requiredType);
		}
		throw new NoSuchBeanDefinitionException(requiredType);
	}

	@SuppressWarnings("unchecked")
	@Nullable
	private <T> NamedBeanHolder<T> resolveNamedBean(
			ResolvableType requiredType, @Nullable Object[] args, boolean nonUniqueAsNull) throws BeansException {

		Assert.notNull(requiredType, "Required type must not be null");
		// 1. 根据类型查找，不会实例化 bean
		//只会读取 BeanDefinition 信息或部分实例化 FactoryBean 来获取 Bean 的类型，该过程中不会实例化 Bean
		String[] candidateNames = getBeanNamesForType(requiredType);

		// 2. 多个类型，如何过滤？
		if (candidateNames.length > 1) {
			List<String> autowireCandidates = new ArrayList<>(candidateNames.length);
			for (String beanName : candidateNames) {
				// 如果容器中定义的beanDefinition.autowireCandidate=false（默认为true）则剔除
				// ①没有定义该beanDefinition或②beanDefinition.autowireCandidate=true时合法
				//TODO 什么场景下会出现：没有定义该BeanDefinition，但根据类型可以查找到该beanName?
				if (!containsBeanDefinition(beanName) || getBeanDefinition(beanName).isAutowireCandidate()) {
					autowireCandidates.add(beanName);
				}
			}
			if (!autowireCandidates.isEmpty()) {
				candidateNames = StringUtils.toStringArray(autowireCandidates);
			}
		}

		// 3. 单个candidateNames，则调用getBean(beanName)实例化该bean
		if (candidateNames.length == 1) {
			String beanName = candidateNames[0];
			return new NamedBeanHolder<>(beanName, (T) getBean(beanName, requiredType.toClass(), args));
		}
		// 4. 多个candidateNames，先尝试是否标注Primary属性，再尝试类上@Priority注解
		else if (candidateNames.length > 1) {
			Map<String, Object> candidates = new LinkedHashMap<>(candidateNames.length);
			for (String beanName : candidateNames) {
				//单例对象
				if (containsSingleton(beanName) && args == null) {
					Object beanInstance = getBean(beanName);
					candidates.put(beanName, (beanInstance instanceof NullBean ? null : beanInstance));
				} else {
					candidates.put(beanName, getType(beanName));
				}
			}
			// 查找 primary Bean，即 beanDefinition.primary=true
			String candidateName = determinePrimaryCandidate(candidates, requiredType.toClass());
			if (candidateName == null) {
				//第三重过滤：比较 Bean 的优先级,@javax.annotation.Priority 的优先级，值越小优先级越高
				candidateName = determineHighestPriorityCandidate(candidates, requiredType.toClass());
			}
			// 4. 过滤后只有一个符合条件，getBean(candidateName)实例化
			if (candidateName != null) {
				Object beanInstance = candidates.get(candidateName);
				if (beanInstance == null || beanInstance instanceof Class) {
					beanInstance = getBean(candidateName, requiredType.toClass(), args);
				}
				return new NamedBeanHolder<>(candidateName, (T) beanInstance);
			}
			// 5. 多个bean，抛出异常
			if (!nonUniqueAsNull) {
				throw new NoUniqueBeanDefinitionException(requiredType, candidates.keySet());
			}
		}

		return null;
	}

	@Override
	@Nullable
	public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName,
									@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {//解决依赖（根据依赖关系找到值）

		// descriptor代表当前需要注入的那个字段，或者方法的参数，也就是注入点
		// ParameterNameDiscovery用于解析方法参数名称

		// 把当前Bean工厂的名字发现器赋值给传进来DependencyDescriptor 类
		// 这里面注意了：有必要说说名字发现器这个东西，具体看下面吧==========还是比较重要的
		// Bean工厂的默认值为：private ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
		descriptor.initParameterNameDiscovery(getParameterNameDiscoverer());

		// 支持到Optional类型的注入，比如我们这样注入：private Optional<GenericBean<Object, Object>> objectGenericBean;
		// 也是能够注入进来的，只是类型变为，Optional[GenericBean(t=obj1, w=2)]
		// 对于Java8中Optional类的处理
		// 1. Optional<T>
		if (Optional.class == descriptor.getDependencyType()) {
			return createOptionalDependency(descriptor, requestingBeanName);
		}
		// 兼容ObjectFactory和ObjectProvider（Spring4.3提供的接口）
		// 关于ObjectFactory和ObjectProvider在依赖注入中的大作用，我觉得是非常有必要再撰文讲解的
		// 对于前面讲到的提早曝光的ObjectFactory的特殊处理
		// 2. ObjectFactory<T>、ObjectProvider<T>
		else if (ObjectFactory.class == descriptor.getDependencyType() ||
				ObjectProvider.class == descriptor.getDependencyType()) {
			return new DependencyObjectProvider(descriptor, requestingBeanName);
		}
		// 支持到了javax.inject.Provider这个类的实现
		// 3. javax.inject.Provider<T>
		else if (javaxInjectProviderClass == descriptor.getDependencyType()) {
			return new Jsr330Factory().createDependencyProvider(descriptor, requestingBeanName);
		} else {
			//getAutowireCandidateResolver()得到ContextAnnotationAutowireCandidateResolver 根据依赖注解信息，找到对应的Bean值信息
			//getLazyResolutionProxyIfNecessary方法，它也是唯一实现。
			//如果字段上带有@Lazy注解，表示进行懒加载 Spring不会立即创建注入属性的实例，而是生成代理对象，来代替实例
			// 4. @Lazy
			Object result = getAutowireCandidateResolver().getLazyResolutionProxyIfNecessary(
					descriptor, requestingBeanName);
			// 如果在@Autowired上面还有个注解@Lazy，那就是懒加载的，是另外一种处理方式（是一门学问）
			// 这里如果不是懒加载的（绝大部分情况都走这里） 就进入核心方法doResolveDependency 下面有分解
			// 5. 正常情况
			if (result == null) {
				result = doResolveDependency(descriptor, requestingBeanName, autowiredBeanNames, typeConverter);
			}
			return result;
		}
	}

	/**
	 * 总结
	 * 1.Spring注入依赖后会保存依赖的beanName，作为下次注入相同属性的捷径。如果存在捷径的话，直接通过保存的beanName获取bean实例
	 * 2.对@Value注解的处理。如果存在，会获取并解析value值
	 * 3.对数组或容器类型的处理。如果是数组或容器类型的话，Spring可以将所有与目标类型匹配的bean实例都注入进去，不需要判断
	 * 1)获取数组或容器单个组件的类型
	 * 2)调用findAutowireCandidates方法，获取与组件类型匹配的Map(beanName -> bean实例)
	 * 3)保存类型匹配的beanNames
	 * 4.非数组、容器类型的处理
	 * 1)调用findAutowireCandidates方法，获取与组件类型匹配的Map(beanName -> bean实例)
	 * 2)如果类型匹配的结果为多个，需要进行筛选(@Primary、优先级、字段名)
	 * 3)如果筛选结果不为空，或者只有一个bean类型匹配，就直接使用该bean
	 * <p>
	 * 1.快速查找： @Autowired 注解处理场景。AutowiredAnnotationBeanPostProcessor 处理 @Autowired 注解时，如果注入的对象只有一个，会将该 bean 对应的名称缓存起来，下次直接通过名称查找会快很多。
	 * 2.注入指定值：@Value 注解处理场景。QualifierAnnotationAutowireCandidateResolver 处理 @Value 注解时，会读取 @Value 对应的值进行注入。如果是 String 要经过三个过程：①占位符处理 -> ②EL 表达式解析 -> ③类型转换，这也是一般的处理过程，BeanDefinitionValueResolver 处理 String 对象也是这个过程。
	 * 3.集合依赖查询：直接全部委托给 resolveMultipleBeans 方法。
	 * 4.单个依赖查询：先调用 findAutowireCandidates 查找所有可用的依赖，如果有多个依赖，则根据规则匹配： @Primary -> @Priority -> ③方法名称或字段名称。
	 *
	 * @param descriptor
	 * @param beanName
	 * @param autowiredBeanNames
	 * @param typeConverter
	 * @return
	 * @throws BeansException
	 */
	@Nullable
	public Object doResolveDependency(DependencyDescriptor descriptor, @Nullable String beanName,
									  @Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {

		// 相当于打个点，记录下当前的步骤位置  返回值为当前的InjectionPoint
		InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
		try {
			// 简单的说就是去Bean工厂的缓存里去看看，有没有名称为此的Bean，有就直接返回，没必要继续往下走了
			// 比如此处的beanName为：objectGenericBean等等
			// 1. 快速查找，根据名称查找。AutowiredAnnotationBeanPostProcessor用到
			Object shortcut = descriptor.resolveShortcut(this);
			if (shortcut != null) {
				return shortcut;
			}

			// 2. 注入指定值，QualifierAnnotationAutowireCandidateResolver解析@Value会用到
			Class<?> type = descriptor.getDependencyType();

			// 处理@Value注解,看看ContextAnnotationAutowireCandidateResolver的getSuggestedValue方法,具体实现在父类 QualifierAnnotationAutowireCandidateResolver中
			Object value = getAutowireCandidateResolver().getSuggestedValue(descriptor);

			// 若存在value值，那就去解析它。使用到了AbstractBeanFactory#resolveEmbeddedValue
			// 也就是使用StringValueResolver处理器去处理一些表达式~~
			if (value != null) {
				if (value instanceof String) {
					// 解析@Value中的占位符
					// 2.1 占位符解析
					String strVal = resolveEmbeddedValue((String) value);
					// 获取到对应的bd
					BeanDefinition bd = (beanName != null && containsBean(beanName) ?
							getMergedBeanDefinition(beanName) : null);
					// 处理EL表达式
					// 2.2 Spring EL 表达式
					value = evaluateBeanDefinitionString(strVal, bd);
				}
				// 通过解析el表达式可能还需要进行类型转换
				//如果需要会进行类型转换后返回结果
				TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
				try {
					// 2.3 类型转换
					return converter.convertIfNecessary(value, type, descriptor.getTypeDescriptor());
				} catch (UnsupportedOperationException ex) {
					// A custom TypeConverter which does not support TypeDescriptor resolution...
					return (descriptor.getField() != null ?
							converter.convertIfNecessary(value, type, descriptor.getField()) :
							converter.convertIfNecessary(value, type, descriptor.getMethodParameter()));
				}
			}

			//对数组、Collection、Map等类型进行处理，也是支持自动注入的。
			//因为是数组或容器，Sprng可以直接把符合类型的bean都注入到数组或容器中，处理逻辑是：
			//1.确定容器或数组的组件类型 if else 分别对待，分别处理
			//2.调用findAutowireCandidates（核心方法）方法，获取与组件类型匹配的Map(beanName -> bean实例)
			//3.将符合beanNames添加到autowiredBeanNames中
			// 3. 集合依赖，如 Array、List、Set、Map。内部查找依赖也是使用findAutowireCandidates
			Object multipleBeans = resolveMultipleBeans(descriptor, beanName, autowiredBeanNames, typeConverter);
			if (multipleBeans != null) {
				return multipleBeans;
			}

			// 根据指定类型可能会找到多个bean
			// 这里返回的既有可能是对象，也有可能是对象的类型
			// 这是因为到这里还不能明确的确定当前bean到底依赖的是哪一个bean
			// 所以如果只会返回这个依赖的类型以及对应名称，最后还需要调用getBean(beanName)
			// 去创建这个Bean

			// 获取所有【类型】匹配的Beans，形成一个Map（此处用Map装，是因为可能不止一个符合条件）
			// 该方法就特别重要了，对泛型类型的匹配、对@Qualifierd的解析都在这里面，下面详情分解
			// 4. 单个依赖查询
			//findAutowireCandidates 返回的为什么是对象类型，而不是实例对象？
			//matchingBeans 中的 Object 对象可能是对象类型，而不全部是实例对象。因为 findAutowireCandidates 方法是根据类型 type 查找名称 beanNames，如果容器中该 beanName 还没有实例化，findAutowireCandidates 不会画蛇添足直接实例化该 bean，当然如果已经实例化了会直接返回这个 bean
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
			// 若没有符合条件的Bean。。。
			// 4.1 没有查找到依赖，判断descriptor.require
			if (matchingBeans.isEmpty()) {
				// 并且是必须的，那就抛出没有找到合适的Bean的异常吧
				// 我们非常熟悉的异常信息：expected at least 1 bean which qualifies as autowire candidate...
				if (isRequired(descriptor)) {
					raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
				}
				return null;
			}

			String autowiredBeanName;
			Object instanceCandidate;

			//如果类型匹配的bean不止一个，Spring需要进行筛选，筛选失败的话继续抛出异常
			// 如果只找到一个该类型的，就不用进这里面来帮忙筛选了~~~~~~~~~
			// 4.2 有多个，如何过滤
			if (matchingBeans.size() > 1) {
				// 根据是否是主Bean
				// 是否是最高优先级的Bean
				// 是否是名称匹配的Bean
				// 来确定具体的需要注入的Bean的名称
				// 到这里可以知道，Spring在查找依赖的时候遵循先类型再名称的原则（没有@Qualifier注解情况下）

				// 该方法作用：从给定的beans里面筛选出一个符合条件的bean，此筛选步骤还是比较重要的，因此看看可以看看下文解释吧
				// 4.2.1 @Primary -> @Priority -> 方法名称或字段名称匹配
				autowiredBeanName = determineAutowireCandidate(matchingBeans, descriptor);
				// 4.2.2 根据是否必须，抛出异常。注意这里如果是集合处理，则返回null
				if (autowiredBeanName == null) {
					// 无法推断出具体的名称
					// 如果依赖是必须的，直接抛出异常
					// 如果依赖不是必须的，但是这个依赖类型不是集合或者数组，那么也抛出异常

					// 如果此Bean是要求的，或者 不是Array、Collection、Map等类型，那就抛出异常NoUniqueBeanDefinitionException
					if (isRequired(descriptor) || !indicatesMultipleBeans(type)) {
						return descriptor.resolveNotUnique(descriptor.getResolvableType(), matchingBeans);
					} else {
						// In case of an optional Collection/Map, silently ignore a non-unique case:
						// possibly it was meant to be an empty collection of multiple regular beans
						// (before 4.3 in particular when we didn't even look for collection beans).
						// Spring4.3之后才有：表示如果是required=false，或者就是List Map类型之类的，即使没有找到Bean，也让它不抱错，因为最多注入的是空集合嘛
						return null;
					}
				}
				instanceCandidate = matchingBeans.get(autowiredBeanName);
			} else {
				// We have exactly one match.
				// 直接找到了一个对应的Bean
				// 仅仅只匹配上一个，走这里 很简单  直接拿出来即可
				// 注意这里直接拿出来的技巧：不用遍历，直接用iterator.next()即可
				Map.Entry<String, Object> entry = matchingBeans.entrySet().iterator().next();
				autowiredBeanName = entry.getKey();
				instanceCandidate = entry.getValue();
			}

			// 把找到的autowiredBeanName 放进去
			// 4.3 到了这，说明有且仅有命中一个
			if (autowiredBeanNames != null) {
				autowiredBeanNames.add(autowiredBeanName);
			}

			// 底层就是调用了beanFactory.getBean(beanName);  确保该实例肯定已经被实例化了的
			// 前面已经说过了，这里可能返回的是Bean的类型，所以需要进一步调用getBean
			// 4.4 实际上调用 getBean(autowiredBeanName, type)。但什么情况下会出现这种场景？
			if (instanceCandidate instanceof Class) {
				instanceCandidate = descriptor.resolveCandidate(autowiredBeanName, type, this);
			}
			// 做一些检查，如果依赖是必须的，查找出来的依赖是一个null,那么报错
			// 查询处理的依赖类型不符合，也报错
			Object result = instanceCandidate;
			if (result instanceof NullBean) {
				if (isRequired(descriptor)) {
					raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
				}
				result = null;
			}
			// 再一次校验，type和result的type类型是否吻合=====
			if (!ClassUtils.isAssignableValue(type, result)) {
				throw new BeanNotOfRequiredTypeException(autowiredBeanName, type, instanceCandidate.getClass());
			}
			return result;
		} finally {
			// 最终把节点归还回来
			ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
		}
	}

	@Nullable
	private Object resolveMultipleBeans(DependencyDescriptor descriptor, @Nullable String beanName,
										@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) {

		Class<?> type = descriptor.getDependencyType();

		if (descriptor instanceof StreamDependencyDescriptor) {
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			Stream<Object> stream = matchingBeans.keySet().stream()
					.map(name -> descriptor.resolveCandidate(name, type, this))
					.filter(bean -> !(bean instanceof NullBean));
			if (((StreamDependencyDescriptor) descriptor).isOrdered()) {
				stream = stream.sorted(adaptOrderComparator(matchingBeans));
			}
			return stream;
		} else if (type.isArray()) {
			Class<?> componentType = type.getComponentType();
			ResolvableType resolvableType = descriptor.getResolvableType();
			Class<?> resolvedArrayType = resolvableType.resolve(type);
			if (resolvedArrayType != type) {
				componentType = resolvableType.getComponentType().resolve();
			}
			if (componentType == null) {
				return null;
			}
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, componentType,
					new MultiElementDescriptor(descriptor));
			if (matchingBeans.isEmpty()) {
				return null;
			}
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
			Object result = converter.convertIfNecessary(matchingBeans.values(), resolvedArrayType);
			if (result instanceof Object[]) {
				Comparator<Object> comparator = adaptDependencyComparator(matchingBeans);
				if (comparator != null) {
					Arrays.sort((Object[]) result, comparator);
				}
			}
			return result;
		} else if (Collection.class.isAssignableFrom(type) && type.isInterface()) {
			Class<?> elementType = descriptor.getResolvableType().asCollection().resolveGeneric();
			if (elementType == null) {
				return null;
			}
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, elementType,
					new MultiElementDescriptor(descriptor));
			if (matchingBeans.isEmpty()) {
				return null;
			}
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
			Object result = converter.convertIfNecessary(matchingBeans.values(), type);
			if (result instanceof List) {
				if (((List<?>) result).size() > 1) {
					Comparator<Object> comparator = adaptDependencyComparator(matchingBeans);
					if (comparator != null) {
						((List<?>) result).sort(comparator);
					}
				}
			}
			return result;
		} else if (Map.class == type) {
			ResolvableType mapType = descriptor.getResolvableType().asMap();
			Class<?> keyType = mapType.resolveGeneric(0);
			if (String.class != keyType) {
				return null;
			}
			Class<?> valueType = mapType.resolveGeneric(1);
			if (valueType == null) {
				return null;
			}
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, valueType,
					new MultiElementDescriptor(descriptor));
			if (matchingBeans.isEmpty()) {
				return null;
			}
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			return matchingBeans;
		} else {
			return null;
		}
	}

	private boolean isRequired(DependencyDescriptor descriptor) {
		return getAutowireCandidateResolver().isRequired(descriptor);
	}

	private boolean indicatesMultipleBeans(Class<?> type) {
		return (type.isArray() || (type.isInterface() &&
				(Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type))));
	}

	@Nullable
	private Comparator<Object> adaptDependencyComparator(Map<String, ?> matchingBeans) {
		Comparator<Object> comparator = getDependencyComparator();
		if (comparator instanceof OrderComparator) {
			return ((OrderComparator) comparator).withSourceProvider(
					createFactoryAwareOrderSourceProvider(matchingBeans));
		} else {
			return comparator;
		}
	}

	private Comparator<Object> adaptOrderComparator(Map<String, ?> matchingBeans) {
		Comparator<Object> dependencyComparator = getDependencyComparator();
		OrderComparator comparator = (dependencyComparator instanceof OrderComparator ?
				(OrderComparator) dependencyComparator : OrderComparator.INSTANCE);
		return comparator.withSourceProvider(createFactoryAwareOrderSourceProvider(matchingBeans));
	}

	private OrderComparator.OrderSourceProvider createFactoryAwareOrderSourceProvider(Map<String, ?> beans) {
		IdentityHashMap<Object, String> instancesToBeanNames = new IdentityHashMap<>();
		beans.forEach((beanName, instance) -> instancesToBeanNames.put(instance, beanName));
		return new FactoryAwareOrderSourceProvider(instancesToBeanNames);
	}

	/**
	 * Find bean instances that match the required type.
	 * Called during autowiring for the specified bean.
	 *
	 * @param beanName     the name of the bean that is about to be wired
	 * @param requiredType the actual type of bean to look for
	 *                     (may be an array component type or collection element type)
	 * @param descriptor   the descriptor of the dependency to resolve
	 * @return a Map of candidate names and candidate instances that match
	 * the required type (never {@code null})
	 * @throws BeansException in case of errors
	 * @see #autowireByType
	 * @see #autowireConstructor
	 * <p>
	 * 根据注解进行依赖注入的主要工作，就是根据标注的字段的类型来搜索符合的bean，并将类型匹配的bean注入到字段中
	 * 总结：
	 * 1.将获取类型匹配的Bean工作交给BeanFactoryUtils.beanNamesForTypeIncludingAncestors。该方法除了当前beanFactory还会递归对父parentFactory进行查找
	 * 2.如果注入类型是特殊类型或其子类，会将特殊类型的实例添加到结果
	 * 3.对结果进行筛选
	 * 1）BeanDefinition的autowireCandidate属性，表示是否允许该bena注入到其他bean中，默认为true
	 * 2）泛型类型的匹配，如果存在的话
	 * 3）Qualifier注解。如果存在Qualifier注解的话，会直接比对Qualifier注解中指定的beanName。需要注意的是，Spring处理自己定义的Qualifier注解，还支持javax.inject.Qualifier注解
	 * 4.如果筛选后，结果为空，Spring会放宽筛选条件，再筛选一次
	 */
	protected Map<String, Object> findAutowireCandidates(
			@Nullable String beanName, Class<?> requiredType, DependencyDescriptor descriptor) {

		// 简单来说，这里就是到容器中查询requiredType类型的所有bean的名称的集合
		// 这里会根据descriptor.isEager()来决定是否要匹配factoryBean类型的Bean
		// 如果isEager()为true,那么会匹配factoryBean，反之，不会

		// 获取类型匹配的bean的beanName列表（包括父容器，但是此时还没有进行泛型的精确匹配）
		//BeanFactoryUtils.beanNamesForTypeIncludingAncestors，该方法除了当前beanFactory还会递归对父parentFactory进行查找
		// 2. 类型查找：本质上递归调用beanFactory#beanNamesForType。先匹配实例类型，再匹配bd。
		//包括 ①外部托管 Bean ②注册 BeanDefinition。类型查找调用 beanFactory#beanNamesForType 方法，详见 Spring IoC 依赖查找之类型自省。我们来看一下如何过滤的。
		//自身引用：isSelfReference 方法判断 beanName 和 candidate 是否是同一个对象，包括两种情况：一是名称完全相同，二是 candidate 对应的工厂对象创建了 beanName
		//是否可以注入：底层实际调用 resolver.isAutowireCandidate 方法进行过滤，包含三重规则：①bd.autowireCandidate=true -> ②泛型匹配 -> ③@Qualifier。下面会详细介绍这个方法。
		String[] candidateNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
				this, requiredType, true, descriptor.isEager());

		//存放结果的Map(beanName -> bena实例)  最终会return的
		Map<String, Object> result = new LinkedHashMap<>(candidateNames.length);

		// 第一步会到resolvableDependencies这个集合中查询是否已经存在了解析好的依赖
		// 像我们之所以能够直接在Bean中注入applicationContext对象
		// 就是因为Spring之前就将这个对象放入了resolvableDependencies集合中

		//如果注入类型是特殊类型或其子类，会将特殊类型的实例添加到结果
		// 哪些特殊类型呢？上面截图有，比如你要注入ApplicationContext、BeanFactory等等
		// 1. Spring IoC 内部依赖 resolvableDependencies-->BeanFactory、ResourceLoader、ApplicationEventPublisher、ApplicationContext
		for (Map.Entry<Class<?>, Object> classObjectEntry : this.resolvableDependencies.entrySet()) {
			Class<?> autowiringType = classObjectEntry.getKey();
			if (autowiringType.isAssignableFrom(requiredType)) {
				Object autowiringValue = classObjectEntry.getValue();
				// 如果resolvableDependencies放入的是一个ObjectFactory类型的依赖
				// 那么在这里会生成一个代理对象
				// 例如，我们可以在controller中直接注入request对象
				// 就是因为，容器启动时就在resolvableDependencies放入了一个键值对
				// 其中key为：Request.class,value为：ObjectFactory
				// 在实际注入时放入的是一个代理对象
				autowiringValue = AutowireUtils.resolveAutowiringValue(autowiringValue, requiredType);
				if (requiredType.isInstance(autowiringValue)) {
					result.put(ObjectUtils.identityToString(autowiringValue), autowiringValue);
					break;
				}
			}
		}
		// 接下来开始对之前查找出来的类型匹配的所有BeanName进行处理
		// candidateNames可能会有多个，这里就要开始过滤了，比如@Qualifier、泛型等等
		for (String candidate : candidateNames) {
			// 不是自引用，什么是自引用？
			// 1.候选的Bean的名称跟需要进行注入的Bean名称相同，意味着，自己注入自己
			// 2.或者候选的Bean对应的factoryBean的名称跟需要注入的Bean名称相同，
			// 也就是说A依赖了B但是B的创建又需要依赖A
			// 要符合注入的条件

			//不是自引用 && 符合注入条件
			// 自引用的判断：找到的候选的Bean的名称和当前Bean名称相等 或者 当前bean名称等于工厂bean的名称~~~~~~~
			// isAutowireCandidate：这个方法非常的关键，判断该bean是否允许注入进来。泛型的匹配就发生在这个方法里，下面会详解
			// 2.1 isSelfReference说明beanName和candidate本质是同一个对象
			//     isAutowireCandidate进一步匹配bd.autowireCandidate、泛型、@Qualifier等进行过滤
			if (!isSelfReference(beanName, candidate) && isAutowireCandidate(candidate, descriptor)) {
				// 2.2 添加到候选对象中
				//如果对象还未实例化，Spring 不会画蛇添足将 candidateName 通过 getName 提前实例化
				//是因为 Spring 的 Bean 生命周期，其实从 Bean 还未实例化就已经开始，Spring 会尽可能的不要初始化该 Bean，除非显式调用 getBean 或不得不实例化时，这点在阅读源码是会感受非常强烈，我们在使用 Spring API 时也要非常注意这点
				addCandidateEntry(result, candidate, descriptor, requiredType);
			}
		}
		// 3. 补偿机制：如果依赖查找无法匹配，怎么办？包含泛型补偿和自身引用补偿两种。
		// 排除自引用的情况下，没有找到一个合适的依赖
		//结果集为空 && 注入属性是非数组、容器类型  那么Spring就会放宽注入条件，然后继续寻找
		// 什么叫放宽：比如泛型不要求精确匹配了、比如自引用的注入等等
		if (result.isEmpty()) {
			boolean multiple = indicatesMultipleBeans(requiredType);
			// Consider fallback matches if the first pass failed to find anything...
			// FallbackMatch：放宽对泛型类型的验证  所以从这里用了一个新的fallbackDescriptor 对象   相当于放宽了泛型的匹配
			// 1.先走fallback逻辑，Spring提供的一个扩展吧，感觉没什么卵用
			// 默认情况下fallback的依赖描述符就是自身
			// 3.1 fallbackDescriptor: 泛型补偿，实际上是允许注入对象类型的泛型存在无法解析的情况
			DependencyDescriptor fallbackDescriptor = descriptor.forFallbackMatch();
			for (String candidate : candidateNames) {
				// 3.2 补偿1：不允许自称依赖，但如果是集合依赖，需要过滤非@Qualifier对象。什么场景?
				if (!isSelfReference(beanName, candidate) && isAutowireCandidate(candidate, fallbackDescriptor) &&
						(!multiple || getAutowireCandidateResolver().hasQualifier(descriptor))) {
					addCandidateEntry(result, candidate, descriptor, requiredType);
				}
			}
			// fallback还是失败
			// 3.3 补偿2：允许自称依赖，但如果是集合依赖，注入的集合依赖中需要过滤自己
			if (result.isEmpty() && !multiple) {
				// Consider self references as a final pass...
				// but in the case of a dependency collection, not the very same bean itself.
				// 处理自引用
				// 从这里可以看出，自引用的优先级是很低的，只有在容器中真正的只有这个Bean能作为
				// 候选者的时候，才会去处理，否则自引用是被排除掉的
				for (String candidate : candidateNames) {
					if (isSelfReference(beanName, candidate) &&
							// 不是一个集合或者
							// 是一个集合，但是beanName跟candidate的factoryBeanName相同
							(!(descriptor instanceof MultiElementDescriptor) || !beanName.equals(candidate)) &&
							isAutowireCandidate(candidate, fallbackDescriptor)) {
						addCandidateEntry(result, candidate, descriptor, requiredType);
					}
				}
			}
		}
		return result;
	}

	/**
	 * Add an entry to the candidate map: a bean instance if available or just the resolved
	 * type, preventing early bean initialization ahead of primary candidate selection.
	 * <p>
	 * // candidates：就是findAutowireCandidates方法要返回的候选集合
	 * // candidateName：当前的这个候选Bean的名称
	 * // descriptor：依赖描述符
	 * // requiredType：依赖的类型
	 */
	private void addCandidateEntry(Map<String, Object> candidates, String candidateName,
								   DependencyDescriptor descriptor, Class<?> requiredType) {

		// 如果依赖是一个集合，或者容器中已经包含这个单例了
		// 那么直接调用getBean方法创建或者获取这个Bean
		// 1. 集合依赖，直接调用 getName(candidateName) 实例化
		if (descriptor instanceof MultiElementDescriptor) {
			Object beanInstance = descriptor.resolveCandidate(candidateName, requiredType, this);
			if (!(beanInstance instanceof NullBean)) {
				candidates.put(candidateName, beanInstance);
			}
		}
		// 2. 已经实例化，直接返回实例对象
		else if (containsSingleton(candidateName) || (descriptor instanceof StreamDependencyDescriptor &&
				((StreamDependencyDescriptor) descriptor).isOrdered())) {
			Object beanInstance = descriptor.resolveCandidate(candidateName, requiredType, this);
			candidates.put(candidateName, (beanInstance instanceof NullBean ? null : beanInstance));
		}
		// 如果依赖的类型不是一个集合，这个时候还不能确定到底要使用哪个依赖，
		// 所以不能将这些Bean创建出来，所以这个时候，放入candidates是Bean的名称以及类型
		// 3. 只获取candidateName的类型，真正需要注入时才实例化对象
		else {
			candidates.put(candidateName, getType(candidateName));
		}
	}

	/**
	 * Determine the autowire candidate in the given set of beans.
	 * <p>Looks for {@code @Primary} and {@code @Priority} (in that order).
	 *
	 * @param candidates a Map of candidate names and candidate instances
	 *                   that match the required type, as returned by {@link #findAutowireCandidates}
	 * @param descriptor the target dependency to match against
	 * @return the name of the autowire candidate, or {@code null} if none found
	 */
	@Nullable
	protected String determineAutowireCandidate(Map<String, Object> candidates, DependencyDescriptor descriptor) {
		Class<?> requiredType = descriptor.getDependencyType();
		// 看看传入的Bean中有没有标注了@Primary注解的
		String primaryCandidate = determinePrimaryCandidate(candidates, requiredType);
		if (primaryCandidate != null) {
			// 如果找到了 就直接返回
			// 由此可见，@Primary的优先级还是非常的高的
			return primaryCandidate;
		}
		//找到一个标注了javax.annotation.Priority注解的。（备注：优先级的值不能有相同的，否则报错）
		String priorityCandidate = determineHighestPriorityCandidate(candidates, requiredType);
		if (priorityCandidate != null) {
			return priorityCandidate;
		}
		// Fallback
		// 这里是最终的处理（相信绝大部分情况下，都会走这里~~~~~~~~~~~~~~~~~~~~）
		// 此处就能看出resolvableDependencies它的效能了，他会把解析过的依赖们缓存起来，不用再重复解析了
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateName = entry.getKey();
			Object beanInstance = entry.getValue();

			// 到这一步就比较简单了，matchesBeanName匹配上Map的key就行。
			// 需要注意的是，bean可能存在很多别名，所以只要有一个别名相同，就认为是能够匹配上的  具体参考AbstractBeanFactory#getAliases方法
			//descriptor.getDependencyName() 这个特别需要注意的是：如果是字段，这里调用的this.field.getName() 直接用的是字段的名称
			// 因此此处我们看到的情况是，我们采用@Autowired虽然匹配到两个类型的Bean了，即使我们没有使用@Qualifier注解，也会根据字段名找到一个合适的（若没找到，就抱错了）
			if ((beanInstance != null && this.resolvableDependencies.containsValue(beanInstance)) ||
					matchesBeanName(candidateName, descriptor.getDependencyName())) {
				return candidateName;
			}
		}
		return null;
	}

	/**
	 * Determine the primary candidate in the given set of beans.
	 *
	 * @param candidates   a Map of candidate names and candidate instances
	 *                     (or candidate classes if not created yet) that match the required type
	 * @param requiredType the target dependency type to match against
	 * @return the name of the primary candidate, or {@code null} if none found
	 * @see #isPrimary(String, Object)
	 * <p>
	 * determinePrimaryCandidate：顾名思义。它是从给定的Bean中看有木有标注了@Primary注解的Bean，优先选择
	 */
	@Nullable
	protected String determinePrimaryCandidate(Map<String, Object> candidates, Class<?> requiredType) {
		String primaryBeanName = null;
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateBeanName = entry.getKey();
			Object beanInstance = entry.getValue();

			// isPrimary就是去看看容器里（包含父容器）对应的Bean定义信息是否有@Primary标注
			if (isPrimary(candidateBeanName, beanInstance)) {
				if (primaryBeanName != null) {
					boolean candidateLocal = containsBeanDefinition(candidateBeanName);
					boolean primaryLocal = containsBeanDefinition(primaryBeanName);

					// 这个相当于如果已经找到了一个@Primary的，然后又找到了一个 那就抛出异常
					// @Primary只能标注到一个同类型的Bean上
					if (candidateLocal && primaryLocal) {
						throw new NoUniqueBeanDefinitionException(requiredType, candidates.size(),
								"more than one 'primary' bean found among candidates: " + candidates.keySet());
					} else if (candidateLocal) {
						// 把找出来的标注了@Primary的Bean的名称返回出去
						primaryBeanName = candidateBeanName;
					}
				} else {
					primaryBeanName = candidateBeanName;
				}
			}
		}
		return primaryBeanName;
	}

	/**
	 * Determine the candidate with the highest priority in the given set of beans.
	 * <p>Based on {@code @javax.annotation.Priority}. As defined by the related
	 * {@link org.springframework.core.Ordered} interface, the lowest value has
	 * the highest priority.
	 *
	 * @param candidates   a Map of candidate names and candidate instances
	 *                     (or candidate classes if not created yet) that match the required type
	 * @param requiredType the target dependency type to match against
	 * @return the name of the candidate with the highest priority,
	 * or {@code null} if none found
	 * @see #getPriority(Object)
	 * <p>
	 * determineHighestPriorityCandidate：从给定的Bean里面筛选出一个优先级最高的
	 * 什么叫优先级最高呢？主要为了兼容JDK6提供的注解javax.annotation.Priority
	 */
	@Nullable
	protected String determineHighestPriorityCandidate(Map<String, Object> candidates, Class<?> requiredType) {
		String highestPriorityBeanName = null;
		Integer highestPriority = null;
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateBeanName = entry.getKey();
			Object beanInstance = entry.getValue();
			if (beanInstance != null) {

				//AnnotationAwareOrderComparator#getPriority
				// 这里就是为了兼容JDK6提供的javax.annotation.Priority这个注解，然后做一个优先级排序
				// 注意注意注意：这里并不是@Order，和它木有任何关系~~~
				// 它有的作用像Spring提供的@Primary注解
				Integer candidatePriority = getPriority(beanInstance);

				// 大部分情况下，我们这里都是null，但是需要注意的是，@Primary只能标注一个，@Priority 虽然可以标注多个，但是里面的优先级值，不能出现相同的（强烈建议不要使用~~~~而使用@Primary）
				if (candidatePriority != null) {
					if (highestPriorityBeanName != null) {

						// 如果优先级的值相等，是不允许的，这里需要引起注意，个人建议一般还是使用@Primary吧
						if (candidatePriority.equals(highestPriority)) {
							throw new NoUniqueBeanDefinitionException(requiredType, candidates.size(),
									"Multiple beans found with the same priority ('" + highestPriority +
											"') among candidates: " + candidates.keySet());
						} else if (candidatePriority < highestPriority) {
							highestPriorityBeanName = candidateBeanName;
							highestPriority = candidatePriority;
						}
					} else {
						highestPriorityBeanName = candidateBeanName;
						highestPriority = candidatePriority;
					}
				}
			}
		}
		return highestPriorityBeanName;
	}

	/**
	 * Return whether the bean definition for the given bean name has been
	 * marked as a primary bean.
	 *
	 * @param beanName     the name of the bean
	 * @param beanInstance the corresponding bean instance (can be null)
	 * @return whether the given bean qualifies as primary
	 */
	protected boolean isPrimary(String beanName, Object beanInstance) {
		String transformedBeanName = transformedBeanName(beanName);
		if (containsBeanDefinition(transformedBeanName)) {
			return getMergedLocalBeanDefinition(transformedBeanName).isPrimary();
		}
		BeanFactory parent = getParentBeanFactory();
		return (parent instanceof DefaultListableBeanFactory &&
				((DefaultListableBeanFactory) parent).isPrimary(transformedBeanName, beanInstance));
	}

	/**
	 * Return the priority assigned for the given bean instance by
	 * the {@code javax.annotation.Priority} annotation.
	 * <p>The default implementation delegates to the specified
	 * {@link #setDependencyComparator dependency comparator}, checking its
	 * {@link OrderComparator#getPriority method} if it is an extension of
	 * Spring's common {@link OrderComparator} - typically, an
	 * {@link org.springframework.core.annotation.AnnotationAwareOrderComparator}.
	 * If no such comparator is present, this implementation returns {@code null}.
	 *
	 * @param beanInstance the bean instance to check (can be {@code null})
	 * @return the priority assigned to that bean or {@code null} if none is set
	 */
	@Nullable
	protected Integer getPriority(Object beanInstance) {
		Comparator<Object> comparator = getDependencyComparator();
		if (comparator instanceof OrderComparator) {
			return ((OrderComparator) comparator).getPriority(beanInstance);
		}
		return null;
	}

	/**
	 * Determine whether the given candidate name matches the bean name or the aliases
	 * stored in this bean definition.
	 */
	protected boolean matchesBeanName(String beanName, @Nullable String candidateName) {
		return (candidateName != null &&
				(candidateName.equals(beanName) || ObjectUtils.containsElement(getAliases(beanName), candidateName)));
	}

	/**
	 * Determine whether the given beanName/candidateName pair indicates a self reference,
	 * i.e. whether the candidate points back to the original bean or to a factory method
	 * on the original bean.
	 */
	private boolean isSelfReference(@Nullable String beanName, @Nullable String candidateName) {
		return (beanName != null && candidateName != null &&
				(beanName.equals(candidateName) || (containsBeanDefinition(candidateName) &&
						beanName.equals(getMergedLocalBeanDefinition(candidateName).getFactoryBeanName()))));
	}

	/**
	 * Raise a NoSuchBeanDefinitionException or BeanNotOfRequiredTypeException
	 * for an unresolvable dependency.
	 */
	private void raiseNoMatchingBeanFound(
			Class<?> type, ResolvableType resolvableType, DependencyDescriptor descriptor) throws BeansException {

		checkBeanNotOfRequiredType(type, descriptor);

		throw new NoSuchBeanDefinitionException(resolvableType,
				"expected at least 1 bean which qualifies as autowire candidate. " +
						"Dependency annotations: " + ObjectUtils.nullSafeToString(descriptor.getAnnotations()));
	}

	/**
	 * Raise a BeanNotOfRequiredTypeException for an unresolvable dependency, if applicable,
	 * i.e. if the target type of the bean would match but an exposed proxy doesn't.
	 */
	private void checkBeanNotOfRequiredType(Class<?> type, DependencyDescriptor descriptor) {
		for (String beanName : this.beanDefinitionNames) {
			try {
				RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
				Class<?> targetType = mbd.getTargetType();
				if (targetType != null && type.isAssignableFrom(targetType) &&
						isAutowireCandidate(beanName, mbd, descriptor, getAutowireCandidateResolver())) {
					// Probably a proxy interfering with target type match -> throw meaningful exception.
					Object beanInstance = getSingleton(beanName, false);
					Class<?> beanType = (beanInstance != null && beanInstance.getClass() != NullBean.class ?
							beanInstance.getClass() : predictBeanType(beanName, mbd));
					if (beanType != null && !type.isAssignableFrom(beanType)) {
						throw new BeanNotOfRequiredTypeException(beanName, type, beanType);
					}
				}
			} catch (NoSuchBeanDefinitionException ex) {
				// Bean definition got removed while we were iterating -> ignore.
			}
		}

		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof DefaultListableBeanFactory) {
			((DefaultListableBeanFactory) parent).checkBeanNotOfRequiredType(type, descriptor);
		}
	}

	/**
	 * Create an {@link Optional} wrapper for the specified dependency.
	 */
	private Optional<?> createOptionalDependency(
			DependencyDescriptor descriptor, @Nullable String beanName, final Object... args) {

		DependencyDescriptor descriptorToUse = new NestedDependencyDescriptor(descriptor) {
			@Override
			public boolean isRequired() {
				return false;
			}

			@Override
			public Object resolveCandidate(String beanName, Class<?> requiredType, BeanFactory beanFactory) {
				return (!ObjectUtils.isEmpty(args) ? beanFactory.getBean(beanName, args) :
						super.resolveCandidate(beanName, requiredType, beanFactory));
			}
		};
		Object result = doResolveDependency(descriptorToUse, beanName, null, null);
		return (result instanceof Optional ? (Optional<?>) result : Optional.ofNullable(result));
	}


	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(ObjectUtils.identityToString(this));
		sb.append(": defining beans [");
		sb.append(StringUtils.collectionToCommaDelimitedString(this.beanDefinitionNames));
		sb.append("]; ");
		BeanFactory parent = getParentBeanFactory();
		if (parent == null) {
			sb.append("root of factory hierarchy");
		} else {
			sb.append("parent: ").append(ObjectUtils.identityToString(parent));
		}
		return sb.toString();
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		throw new NotSerializableException("DefaultListableBeanFactory itself is not deserializable - " +
				"just a SerializedBeanFactoryReference is");
	}

	protected Object writeReplace() throws ObjectStreamException {
		if (this.serializationId != null) {
			return new SerializedBeanFactoryReference(this.serializationId);
		} else {
			throw new NotSerializableException("DefaultListableBeanFactory has no serialization id");
		}
	}


	/**
	 * Minimal id reference to the factory.
	 * Resolved to the actual factory instance on deserialization.
	 */
	private static class SerializedBeanFactoryReference implements Serializable {

		private final String id;

		public SerializedBeanFactoryReference(String id) {
			this.id = id;
		}

		private Object readResolve() {
			Reference<?> ref = serializableFactories.get(this.id);
			if (ref != null) {
				Object result = ref.get();
				if (result != null) {
					return result;
				}
			}
			// Lenient fallback: dummy factory in case of original factory not found...
			DefaultListableBeanFactory dummyFactory = new DefaultListableBeanFactory();
			dummyFactory.serializationId = this.id;
			return dummyFactory;
		}
	}


	/**
	 * A dependency descriptor marker for nested elements.
	 */
	private static class NestedDependencyDescriptor extends DependencyDescriptor {

		public NestedDependencyDescriptor(DependencyDescriptor original) {
			super(original);
			increaseNestingLevel();
		}
	}


	/**
	 * A dependency descriptor for a multi-element declaration with nested elements.
	 */
	private static class MultiElementDescriptor extends NestedDependencyDescriptor {

		public MultiElementDescriptor(DependencyDescriptor original) {
			super(original);
		}
	}


	/**
	 * A dependency descriptor marker for stream access to multiple elements.
	 */
	private static class StreamDependencyDescriptor extends DependencyDescriptor {

		private final boolean ordered;

		public StreamDependencyDescriptor(DependencyDescriptor original, boolean ordered) {
			super(original);
			this.ordered = ordered;
		}

		public boolean isOrdered() {
			return this.ordered;
		}
	}


	private interface BeanObjectProvider<T> extends ObjectProvider<T>, Serializable {
	}


	/**
	 * Serializable ObjectFactory/ObjectProvider for lazy resolution of a dependency.
	 */
	private class DependencyObjectProvider implements BeanObjectProvider<Object> {

		private final DependencyDescriptor descriptor;

		private final boolean optional;

		@Nullable
		private final String beanName;

		public DependencyObjectProvider(DependencyDescriptor descriptor, @Nullable String beanName) {
			this.descriptor = new NestedDependencyDescriptor(descriptor);
			this.optional = (this.descriptor.getDependencyType() == Optional.class);
			this.beanName = beanName;
		}

		@Override
		public Object getObject() throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName);
			} else {
				Object result = doResolveDependency(this.descriptor, this.beanName, null, null);
				if (result == null) {
					throw new NoSuchBeanDefinitionException(this.descriptor.getResolvableType());
				}
				return result;
			}
		}

		@Override
		public Object getObject(final Object... args) throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName, args);
			} else {
				DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
					@Override
					public Object resolveCandidate(String beanName, Class<?> requiredType, BeanFactory beanFactory) {
						return beanFactory.getBean(beanName, args);
					}
				};
				Object result = doResolveDependency(descriptorToUse, this.beanName, null, null);
				if (result == null) {
					throw new NoSuchBeanDefinitionException(this.descriptor.getResolvableType());
				}
				return result;
			}
		}

		@Override
		@Nullable
		public Object getIfAvailable() throws BeansException {
			try {
				if (this.optional) {
					return createOptionalDependency(this.descriptor, this.beanName);
				} else {
					DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
						@Override
						public boolean isRequired() {
							return false;
						}
					};
					return doResolveDependency(descriptorToUse, this.beanName, null, null);
				}
			} catch (ScopeNotActiveException ex) {
				// Ignore resolved bean in non-active scope
				return null;
			}
		}

		@Override
		public void ifAvailable(Consumer<Object> dependencyConsumer) throws BeansException {
			Object dependency = getIfAvailable();
			if (dependency != null) {
				try {
					dependencyConsumer.accept(dependency);
				} catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope, even on scoped proxy invocation
				}
			}
		}

		@Override
		@Nullable
		public Object getIfUnique() throws BeansException {
			DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
				@Override
				public boolean isRequired() {
					return false;
				}

				@Override
				@Nullable
				public Object resolveNotUnique(ResolvableType type, Map<String, Object> matchingBeans) {
					return null;
				}
			};
			try {
				if (this.optional) {
					return createOptionalDependency(descriptorToUse, this.beanName);
				} else {
					return doResolveDependency(descriptorToUse, this.beanName, null, null);
				}
			} catch (ScopeNotActiveException ex) {
				// Ignore resolved bean in non-active scope
				return null;
			}
		}

		@Override
		public void ifUnique(Consumer<Object> dependencyConsumer) throws BeansException {
			Object dependency = getIfUnique();
			if (dependency != null) {
				try {
					dependencyConsumer.accept(dependency);
				} catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope, even on scoped proxy invocation
				}
			}
		}

		@Nullable
		protected Object getValue() throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName);
			} else {
				return doResolveDependency(this.descriptor, this.beanName, null, null);
			}
		}

		@Override
		public Stream<Object> stream() {
			return resolveStream(false);
		}

		@Override
		public Stream<Object> orderedStream() {
			return resolveStream(true);
		}

		@SuppressWarnings("unchecked")
		private Stream<Object> resolveStream(boolean ordered) {
			DependencyDescriptor descriptorToUse = new StreamDependencyDescriptor(this.descriptor, ordered);
			Object result = doResolveDependency(descriptorToUse, this.beanName, null, null);
			return (result instanceof Stream ? (Stream<Object>) result : Stream.of(result));
		}
	}


	/**
	 * Separate inner class for avoiding a hard dependency on the {@code javax.inject} API.
	 * Actual {@code javax.inject.Provider} implementation is nested here in order to make it
	 * invisible for Graal's introspection of DefaultListableBeanFactory's nested classes.
	 */
	private class Jsr330Factory implements Serializable {

		public Object createDependencyProvider(DependencyDescriptor descriptor, @Nullable String beanName) {
			return new Jsr330Provider(descriptor, beanName);
		}

		private class Jsr330Provider extends DependencyObjectProvider implements Provider<Object> {

			public Jsr330Provider(DependencyDescriptor descriptor, @Nullable String beanName) {
				super(descriptor, beanName);
			}

			@Override
			@Nullable
			public Object get() throws BeansException {
				return getValue();
			}
		}
	}


	/**
	 * An {@link org.springframework.core.OrderComparator.OrderSourceProvider} implementation
	 * that is aware of the bean metadata of the instances to sort.
	 * <p>Lookup for the method factory of an instance to sort, if any, and let the
	 * comparator retrieve the {@link org.springframework.core.annotation.Order}
	 * value defined on it. This essentially allows for the following construct:
	 */
	private class FactoryAwareOrderSourceProvider implements OrderComparator.OrderSourceProvider {

		private final Map<Object, String> instancesToBeanNames;

		public FactoryAwareOrderSourceProvider(Map<Object, String> instancesToBeanNames) {
			this.instancesToBeanNames = instancesToBeanNames;
		}

		@Override
		@Nullable
		public Object getOrderSource(Object obj) {
			String beanName = this.instancesToBeanNames.get(obj);
			if (beanName == null || !containsBeanDefinition(beanName)) {
				return null;
			}
			RootBeanDefinition beanDefinition = getMergedLocalBeanDefinition(beanName);
			List<Object> sources = new ArrayList<>(2);
			Method factoryMethod = beanDefinition.getResolvedFactoryMethod();
			if (factoryMethod != null) {
				sources.add(factoryMethod);
			}
			Class<?> targetType = beanDefinition.getTargetType();
			if (targetType != null && targetType != obj.getClass()) {
				sources.add(targetType);
			}
			return sources.toArray();
		}
	}

}
